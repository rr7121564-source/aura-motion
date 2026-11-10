package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.finiteFloat
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val colors: IntArray,
    val triangles: IntArray,
    val slots: IntArray,
    val textures: List<ByteArray>,
    val twoSided: Boolean,
    val rig: ModelRig? = null,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3
}

const val MODEL_TRIANGLE_BUDGET = 50_000
const val MODEL_HIGH_DETAIL_BUDGET = 100_000

fun triangles(count: Int): String = java.text.NumberFormat.getIntegerInstance().format(count)

object ModelDetail {
    val budget = MutableStateFlow(MODEL_TRIANGLE_BUDGET)
    val high: Boolean get() = budget.value > MODEL_TRIANGLE_BUDGET
}

internal const val MODEL_WORK_LIMIT = 16_000_000L

internal fun modelFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').replace('\u0000', '_')
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "model"

fun parseModel(name: String, bytes: ByteArray, open: (String) -> ByteArray?): Mesh {
    val mesh = if (name.substringAfterLast('.').lowercase() == "obj") ObjReader.read(bytes.toString(Charsets.UTF_8), open) else GltfReader.read(bytes, open)
    require(mesh.triangleCount > 0) { AppText.get(R.string.model_err_no_triangles) }
    require(mesh.positions.all(Float::isFinite) && mesh.normals.all(Float::isFinite) && mesh.uvs.all(Float::isFinite)) {
        AppText.get(R.string.model_err_accessor)
    }
    return mesh
}

fun readModel(name: String, bytes: ByteArray, open: (String) -> ByteArray?): Mesh = decimated(parseModel(name, bytes, open), ModelDetail.budget.value)

private class Floats {
    var data = FloatArray(4096)
    var size = 0
    fun reserve(extra: Int) { if (size + extra > data.size) data = data.copyOf(size + extra) }
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun array() = data.copyOf(size)
}

private class Ints {
    var data = IntArray(4096)
    var size = 0
    fun reserve(extra: Int) { if (size + extra > data.size) data = data.copyOf(size + extra) }
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun array() = data.copyOf(size)
}

private class MeshBuilder {
    var rig: RigBuild? = null
    val positions = Floats()
    val normals = Floats()
    val uvs = Floats()
    val colors = Ints()
    val triangles = Ints()
    val slots = Ints()
    private val missing = Ints()
    val count get() = colors.size

    fun reserve(vertices: Int, corners: Int) {
        positions.reserve(vertices * 3); normals.reserve(vertices * 3); uvs.reserve(vertices * 2); colors.reserve(vertices)
        triangles.reserve(corners); slots.reserve(corners / 3)
    }

    fun vertex(x: Float, y: Float, z: Float, normal: FloatArray?, u: Float, v: Float, color: Int, rigVertex: RigVertex? = null): Int {
        positions.add(x); positions.add(-y); positions.add(-z)
        if (normal == null) { missing.add(count); repeat(3) { normals.add(0f) } }
        else { normals.add(normal[0]); normals.add(-normal[1]); normals.add(-normal[2]) }
        uvs.add(u); uvs.add(v)
        colors.add(color)
        rig?.add(requireNotNull(rigVertex))
        return count - 1
    }

    fun triangle(a: Int, b: Int, c: Int, slot: Int) {
        if (a == b || b == c || a == c) return
        triangles.add(a); triangles.add(b); triangles.add(c)
        slots.add(slot)
    }

    fun build(textures: List<ByteArray>, twoSided: Boolean): Mesh {
        if (missing.size > 0) flatten()
        val remap = IntArray(count) { -1 }
        var used = 0
        for (k in 0 until triangles.size) {
            val v = triangles.data[k]
            if (remap[v] < 0) remap[v] = used++
            triangles.data[k] = remap[v]
        }
        val keep = IntArray(used).also { k -> remap.forEachIndexed { v, to -> if (to >= 0) k[to] = v } }
        fun pick(source: Floats, n: Int) = FloatArray(used * n) { source.data[keep[it / n] * n + it % n] }
        val p = pick(positions, 3)
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices) { lo[i % 3] = min(lo[i % 3], p[i]); hi[i % 3] = max(hi[i % 3], p[i]) }
        val centre = FloatArray(3) { (lo[it] + hi[it]) / 2 }
        var radius = 0f
        for (i in p.indices step 3) {
            val dx = p[i] - centre[0]; val dy = p[i + 1] - centre[1]; val dz = p[i + 2] - centre[2]
            radius = max(radius, dx * dx + dy * dy + dz * dz)
        }
        radius = sqrt(radius).takeIf { it > 0f } ?: 1f
        for (i in p.indices) p[i] = (p[i] - centre[i % 3]) / radius
        return Mesh(p, pick(normals, 3), pick(uvs, 2), IntArray(used) { colors.data[keep[it]] }, triangles.array(), slots.array(), textures, twoSided,
            rig?.build(keep, centre, radius))
    }

    private fun flatten() {
        val lacks = BooleanArray(count)
        for (k in 0 until missing.size) lacks[missing.data[k]] = true
        for (k in 0 until triangles.size step 3) {
            val corner = IntArray(3) { triangles.data[k + it] }
            if (corner.none { lacks[it] }) continue
            val p = positions.data
            val (a, b, c) = corner.map { it * 3 }
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val f = floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            val l = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]).takeIf { it > 0f } ?: continue
            for (i in 0..2) {
                if (!lacks[corner[i]]) continue
                val v = corner[i]
                for (j in 0..2) { positions.add(positions.data[v * 3 + j]); normals.add(f[j] / l) }
                uvs.add(uvs.data[v * 2]); uvs.add(uvs.data[v * 2 + 1])
                colors.add(colors.data[v])
                rig?.duplicate(v)
                triangles.data[k + i] = count - 1
            }
        }
    }
}

private data class RigVertex(val p: FloatArray, val n: FloatArray, val joints: IntArray, val weights: FloatArray)
private class RigBuild(val nodes: List<ModelRigNode>, val clips: List<ModelRigClip>) {
    val bindings = ArrayList<ModelRigBinding>()
    private val positions = Floats()
    private val normals = Floats()
    private val influences = Ints()
    private val weights = Floats()
    fun add(v: RigVertex) {
        v.p.forEach(positions::add); v.n.forEach(normals::add)
        repeat(8) { influences.add(v.joints.getOrElse(it) { -1 }); weights.add(v.weights.getOrElse(it) { 0f }) }
    }
    fun duplicate(v: Int) {
        repeat(3) { positions.add(positions.data[v * 3 + it]); normals.add(normals.data[v * 3 + it]) }
        repeat(8) { influences.add(influences.data[v * 8 + it]); weights.add(weights.data[v * 8 + it]) }
    }
    fun build(keep: IntArray, centre: FloatArray, radius: Float): ModelRig {
        val h = floatArrayOf(1/radius,0f,0f,0f, 0f,-1/radius,0f,0f, 0f,0f,-1/radius,0f,
            -centre[0]/radius,-centre[1]/radius,-centre[2]/radius,1f)
        return ModelRig(nodes, clips, bindings, h,
            FloatArray(keep.size * 3) { positions.data[keep[it / 3] * 3 + it % 3] },
            FloatArray(keep.size * 3) { normals.data[keep[it / 3] * 3 + it % 3] },
            IntArray(keep.size * 8) { influences.data[keep[it / 8] * 8 + it % 8] },
            FloatArray(keep.size * 8) { weights.data[keep[it / 8] * 8 + it % 8] })
    }
}

private fun argb(r: Float, g: Float, b: Float, a: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
    return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

internal fun polygonTriangles(xs: FloatArray, ys: FloatArray, zs: FloatArray): IntArray {
    val n = xs.size
    val fan = IntArray(max(0, n - 2) * 3) { i -> if (i % 3 == 0) 0 else i / 3 + i % 3 }
    if (n <= 3 || n > 4096) return fan
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    for (i in 0 until n) {
        val j = (i + 1) % n
        nx += (ys[i] - ys[j]).toDouble() * (zs[i] + zs[j])
        ny += (zs[i] - zs[j]).toDouble() * (xs[i] + xs[j])
        nz += (xs[i] - xs[j]).toDouble() * (ys[i] + ys[j])
    }
    val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
    if (max(ax, max(ay, az)) == 0.0) return fan
    val u: FloatArray; val v: FloatArray; val sign: Double
    when {
        az >= ax && az >= ay -> { u = xs; v = ys; sign = nz }
        ax >= ay -> { u = ys; v = zs; sign = nx }
        else -> { u = zs; v = xs; sign = ny }
    }
    val flip = sign < 0
    fun cross(a: Int, b: Int, c: Int): Double {
        val r = (u[b] - u[a]).toDouble() * (v[c] - v[a]) - (v[b] - v[a]).toDouble() * (u[c] - u[a])
        return if (flip) -r else r
    }
    val left = ArrayList<Int>(n).apply { for (i in 0 until n) add(i) }
    val out = IntArray((n - 2) * 3)
    var written = 0
    var miss = 0
    var i = 0
    while (left.size > 3 && miss < left.size) {
        val m = left.size
        val a = left[(i + m - 1) % m]; val b = left[i % m]; val c = left[(i + 1) % m]
        var ear = cross(a, b, c) > 0
        if (ear) for (k in left) {
            if (k == a || k == b || k == c) continue
            if (cross(a, b, k) >= 0 && cross(b, c, k) >= 0 && cross(c, a, k) >= 0) { ear = false; break }
        }
        if (ear) {
            out[written++] = a; out[written++] = b; out[written++] = c
            left.removeAt(i % m)
            miss = 0
            if (i >= left.size) i = 0
        } else {
            miss++
            i = (i + 1) % m
        }
    }
    for (k in 1 until left.size - 1) { out[written++] = left[0]; out[written++] = left[k]; out[written++] = left[k + 1] }
    return out
}

private fun tokens(line: String): List<String> = line.split(' ', '\t').filter { it.isNotEmpty() }

private fun mapFileName(args: String): String? {
    fun split(s: String): Pair<String, String> { val w = s.takeWhile { !it.isWhitespace() }; return w to s.substring(w.length).trimStart() }
    var rest = args.trim()
    while (rest.startsWith("-")) {
        val (option, after) = split(rest)
        rest = after
        var count = when (option.lowercase()) { "-s", "-o", "-t" -> 3; "-mm" -> 2; else -> 1 }
        while (count-- > 0) {
            val (arg, tail) = split(rest)
            if (arg.toFloatOrNull() == null && arg.lowercase() !in setOf("on", "off", "r", "g", "b", "m", "l", "z")) break
            rest = tail
        }
    }
    return rest.takeIf { it.isNotEmpty() }
}

object ObjReader {
    private const val LIMIT = (1 shl 21) - 1

    private class Material(var color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f), var texture: String? = null)

    fun read(text: String, open: (String) -> ByteArray?, limit: Long = MODEL_WORK_LIMIT): Mesh {
        var work = 0L
        val vs = Floats()
        val vcs = Floats()
        val vts = Floats()
        val vns = Floats()
        val materials = HashMap<String, Material>()
        val textures = ArrayList<ByteArray>()
        val textureSlots = HashMap<String, Int>()
        val builder = MeshBuilder()
        val seen = HashMap<Long, Int>()
        var material = Material()
        var slot = -1
        fun resolve(index: Int, count: Int) = if (index < 0) count + index else index - 1
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = tokens(line)
            if (t[0] == "v" || t[0] == "vt" || t[0] == "vn" || t[0] == "f") {
                work += if (t[0] == "f") t.size - 1L else 1L
                require(work <= limit) { AppText.get(R.string.model_err_too_complex) }
            }
            when (t[0]) {
                "v" -> {
                    for (i in 1..3) vs.add(t.getOrNull(i)?.finiteFloat() ?: 0f)
                    for (i in 4..6) vcs.add(t.getOrNull(i)?.finiteFloat() ?: 1f)
                }
                "vt" -> { vts.add(t.getOrNull(1)?.finiteFloat() ?: 0f); vts.add(t.getOrNull(2)?.finiteFloat() ?: 0f) }
                "vn" -> for (i in 1..3) vns.add(t.getOrNull(i)?.finiteFloat() ?: 0f)
                "mtllib" -> open(line.substringAfter("mtllib").trim())?.let { readMaterials(it.toString(Charsets.UTF_8), materials) }
                "usemtl" -> {
                    material = materials[line.substringAfter("usemtl").trim()] ?: Material()
                    seen.clear()
                    slot = material.texture?.let { name ->
                        textureSlots.getOrPut(name) { open(name)?.let { textures += it; textures.size - 1 } ?: -1 }
                    } ?: -1
                }
                "f" -> {
                    val corners = IntArray(t.size - 1)
                    for (k in 1 until t.size) {
                        val parts = t[k].split('/')
                        val v = resolve(parts[0].toIntOrNull() ?: 0, vs.size / 3)
                        val vt = parts.getOrNull(1)?.toIntOrNull()?.let { resolve(it, vts.size / 2) } ?: -1
                        val vn = parts.getOrNull(2)?.toIntOrNull()?.let { resolve(it, vns.size / 3) } ?: -1
                        require(v in 0 until vs.size / 3) { AppText.get(R.string.model_err_missing_vertex) }
                        require(v < LIMIT && vt < LIMIT && vn < LIMIT) { AppText.get(R.string.model_err_too_large) }
                        val key = (v.toLong() shl 42) or ((vt + 1).toLong() shl 21) or (vn + 1).toLong()
                        corners[k - 1] = seen.getOrPut(key) {
                            val c = material.color
                            val normal = if (vn in 0 until vns.size / 3) floatArrayOf(vns.data[vn * 3], vns.data[vn * 3 + 1], vns.data[vn * 3 + 2]) else null
                            val hasUv = vt in 0 until vts.size / 2
                            builder.vertex(vs.data[v * 3], vs.data[v * 3 + 1], vs.data[v * 3 + 2], normal,
                                if (hasUv) vts.data[vt * 2] else 0f, if (hasUv) 1f - vts.data[vt * 2 + 1] else 0f,
                                argb(c[0] * vcs.data[v * 3], c[1] * vcs.data[v * 3 + 1], c[2] * vcs.data[v * 3 + 2], c[3]))
                        }
                    }
                    if (corners.size == 3) builder.triangle(corners[0], corners[1], corners[2], slot)
                    else {
                        val at = { k: Int, axis: Int -> val v = resolve(t[k + 1].substringBefore('/').toIntOrNull() ?: 0, vs.size / 3); vs.data[v * 3 + axis] }
                        val order = polygonTriangles(FloatArray(corners.size) { at(it, 0) }, FloatArray(corners.size) { at(it, 1) }, FloatArray(corners.size) { at(it, 2) })
                        for (k in order.indices step 3) builder.triangle(corners[order[k]], corners[order[k + 1]], corners[order[k + 2]], slot)
                    }
                }
            }
        }
        return builder.build(textures, false)
    }

    private fun readMaterials(text: String, into: HashMap<String, Material>) {
        var current: Material? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = tokens(line)
            when (t[0]) {
                "newmtl" -> current = Material().also { into[line.substringAfter("newmtl").trim()] = it }
                "Kd" -> current?.let { m -> for (i in 0..2) m.color[i] = t.getOrNull(i + 1)?.finiteFloat() ?: 1f }
                "d" -> current?.let { m -> m.color[3] = t.getOrNull(1)?.finiteFloat() ?: 1f }
                "Tr" -> current?.let { m -> m.color[3] = 1f - (t.getOrNull(1)?.finiteFloat() ?: 0f) }
                "map_Kd" -> current?.texture = mapFileName(line.substring("map_Kd".length))
            }
        }
    }
}

object GltfReader {
    private const val GLB = 0x46546C67
    private const val JSON = 0x4E4F534A
    private const val BIN = 0x004E4942

    fun read(bytes: ByteArray, open: (String) -> ByteArray?, limit: Long = MODEL_WORK_LIMIT): Mesh {
        var bin: ByteBuffer? = null
        val json = if (bytes.size >= 12 && le(bytes, 0) == GLB) {
            var at = 12
            var text: String? = null
            while (at + 8 <= bytes.size) {
                val length = le(bytes, at)
                val type = le(bytes, at + 4)
                val start = at + 8
                require(length >= 0 && start + length <= bytes.size) { AppText.get(R.string.model_err_truncated) }
                if (type == JSON && text == null) text = String(bytes, start, length, Charsets.UTF_8)
                else if (type == BIN && bin == null) bin = ByteBuffer.wrap(bytes, start, length).slice()
                at = start + ((length + 3) and 3.inv())
            }
            JSONObject(requireNotNull(text) { "The model has no description" })
        } else JSONObject(bytes.toString(Charsets.UTF_8))
        val buffers = json.optJSONArray("buffers").objects().map { b -> b.optString("uri").let { uri -> if (uri.isEmpty()) bin else load(uri, open)?.let(ByteBuffer::wrap) } }
        val views = json.optJSONArray("bufferViews").objects()
        val accessors = json.optJSONArray("accessors").objects()
        val meshes = json.optJSONArray("meshes").objects()
        val nodes = json.optJSONArray("nodes").objects()
        val skins = json.optJSONArray("skins").objects()
        val animations = json.optJSONArray("animations").objects()
        require(nodes.size <= 8192 && skins.size <= 1024 && animations.size <= 1024) { AppText.get(R.string.model_err_too_complex) }
        val materials = json.optJSONArray("materials").objects()
        val textureList = json.optJSONArray("textures").objects()
        val images = json.optJSONArray("images").objects()
        val textures = ArrayList<ByteArray>()
        val imageSlots = HashMap<Int, Int>()
        var twoSided = false
        val builder = MeshBuilder()

        fun slice(viewIndex: Int): Triple<ByteBuffer, Int, Int>? {
            val view = views.getOrNull(viewIndex) ?: return null
            val data = buffers.getOrNull(view.optInt("buffer", -1)) ?: return null
            val offset = view.optInt("byteOffset", 0)
            val length = view.optInt("byteLength", 0)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= data.capacity()) { AppText.get(R.string.model_err_buffer_view) }
            return Triple(data.duplicate().apply { limit(offset + length) }.order(ByteOrder.LITTLE_ENDIAN), offset, view.optInt("byteStride", 0))
        }

        fun read(index: Int, normalizeInts: Boolean = true, expect: Int = -1): Pair<FloatArray, Int>? {
            val a = accessors.getOrNull(index) ?: return null
            val count = a.optInt("count", 0)
            require(count >= 0 && count <= limit) { AppText.get(R.string.model_err_too_complex) }
            if (expect >= 0 && count != expect) return null
            val comps = when (a.optString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> return null }
            val type = a.optInt("componentType")
            val size = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> return null }
            require(count.toLong() * comps <= limit * 4 && (comps != 16 || type == 5126)) { AppText.get(R.string.model_err_too_complex) }
            if (!a.has("bufferView") && !a.has("sparse")) return if (expect >= 0) FloatArray(count * comps) to comps else null
            val scaled = normalizeInts && (a.optBoolean("normalized") || type == 5121 || type == 5123)
            fun number(buffer: ByteBuffer, at: Int): Float = when (type) {
                    5126 -> buffer.getFloat(at)
                    5121 -> (buffer.get(at).toInt() and 0xff).let { if (scaled) it / 255f else it.toFloat() }
                    5123 -> (buffer.getShort(at).toInt() and 0xffff).let { if (scaled) it / 65535f else it.toFloat() }
                    5125 -> (buffer.getInt(at).toLong() and 0xffffffffL).toFloat()
                    5120 -> buffer.get(at).toFloat().let { if (scaled) max(it / 127f, -1f) else it }
                    else -> buffer.getShort(at).toFloat().let { if (scaled) max(it / 32767f, -1f) else it }
            }
            val out: FloatArray
            if (a.has("bufferView")) {
                val (buffer, start, viewStride) = slice(a.optInt("bufferView", -1)) ?: return null
                val stride = if (viewStride > 0) viewStride else comps * size
                val base = start + a.optInt("byteOffset", 0).toLong()
                require(stride >= comps * size && base >= start && (count == 0 || base + (count - 1L) * stride + comps * size <= buffer.limit())) { AppText.get(R.string.model_err_accessor) }
                out = FloatArray(count * comps)
                for (e in 0 until count) for (c in 0 until comps) out[e * comps + c] = number(buffer, (base + e.toLong() * stride + c * size).toInt())
            } else {
                require(count.toLong() * comps <= minOf(limit, 4_000_000L)) { AppText.get(R.string.model_err_too_complex) }
                out = FloatArray(count * comps)
            }
            a.optJSONObject("sparse")?.let { sparse ->
                val n = sparse.optInt("count", -1)
                val ids = sparse.optJSONObject("indices") ?: error(AppText.get(R.string.model_err_accessor))
                val values = sparse.optJSONObject("values") ?: error(AppText.get(R.string.model_err_accessor))
                val (ib, ibase, _) = slice(ids.optInt("bufferView", -1)) ?: error(AppText.get(R.string.model_err_accessor))
                val (vb, vbase, _) = slice(values.optInt("bufferView", -1)) ?: error(AppText.get(R.string.model_err_accessor))
                val itype = ids.optInt("componentType")
                val isize = when (itype) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> 0 }
                val io = ibase.toLong() + ids.optInt("byteOffset", 0)
                val vo = vbase.toLong() + values.optInt("byteOffset", 0)
                require(n in 0..count && isize > 0 && io >= ibase && vo >= vbase && io + n.toLong() * isize <= ib.limit() && vo + n.toLong() * comps * size <= vb.limit()) { AppText.get(R.string.model_err_accessor) }
                var previous = -1
                repeat(n) { k ->
                    val at = (io + k * isize).toInt()
                    val id = when (itype) { 5121 -> ib.get(at).toInt() and 255; 5123 -> ib.getShort(at).toInt() and 65535; else -> ib.getInt(at) }
                    require(id > previous && id < count) { AppText.get(R.string.model_err_accessor) }
                    previous = id
                    repeat(comps) { c -> out[id * comps + c] = number(vb, (vo + (k.toLong() * comps + c) * size).toInt()) }
                }
            }
            require(out.all(Float::isFinite)) { AppText.get(R.string.model_err_accessor) }
            return out to comps
        }

        fun image(index: Int): ByteArray? {
            val img = images.getOrNull(index) ?: return null
            val uri = img.optString("uri")
            if (uri.isNotEmpty()) return load(uri, open)
            val (buffer, start, _) = slice(img.optInt("bufferView", -1)) ?: return null
            return ByteArray(buffer.limit() - start).also { buffer.position(start); buffer.get(it) }
        }

        fun indices(primitive: JSONObject, count: Int): IntArray {
            if (!primitive.has("indices")) return IntArray(count) { it }
            val accessor = accessors.getOrNull(primitive.optInt("indices", -1))
                ?: throw IllegalArgumentException(AppText.get(R.string.model_err_accessor))
            if (!accessor.has("bufferView")) return IntArray(0)
            val count = accessor.optInt("count", 0)
            val type = accessor.optInt("componentType")
            val size = when (type) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> throw IllegalArgumentException(AppText.get(R.string.model_err_accessor)) }
            require(count <= limit && accessor.optString("type") == "SCALAR") { AppText.get(R.string.model_err_accessor) }
            val (buffer, start, viewStride) = slice(accessor.optInt("bufferView", -1)) ?: throw IllegalArgumentException(AppText.get(R.string.model_err_accessor))
            val stride = if (viewStride > 0) viewStride else size
            val base = start + accessor.optInt("byteOffset", 0).toLong()
            require(count >= 0 && base >= 0 && (count == 0 || base + (count - 1L) * stride + size <= buffer.limit())) { AppText.get(R.string.model_err_accessor) }
            return IntArray(count) { e ->
                val at = (base + e.toLong() * stride).toInt()
                when (type) {
                    5121 -> buffer.get(at).toInt() and 0xff
                    5123 -> buffer.getShort(at).toInt() and 0xffff
                    else -> buffer.getInt(at).let { if (it < 0) Int.MAX_VALUE else it }
                }
            }
        }

        var work = 0L
        fun charge(accessor: Int) {
            work += accessors.getOrNull(accessor)?.optInt("count", 0)?.coerceAtLeast(0) ?: 0
            require(work <= limit) { AppText.get(R.string.model_err_too_complex) }
        }

        val identity = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        val parents = IntArray(nodes.size) { -1 }
        for ((parent, node) in nodes.withIndex()) node.optJSONArray("children")?.let { children ->
            repeat(children.length()) { k ->
                val child = children.optInt(k, -1)
                if (skins.isNotEmpty() || animations.isNotEmpty()) require(child in nodes.indices && parents[child] == -1 && child != parent) { AppText.get(R.string.model_err_accessor) }
                if (child in nodes.indices && parents[child] == -1) parents[child] = parent
            }
        }
        val local = nodes.map(::localMatrix)
        val worlds = arrayOfNulls<FloatArray>(nodes.size)
        val computing = BooleanArray(nodes.size)
        fun restWorld(index: Int, depth: Int = 0): FloatArray {
            require(index in nodes.indices && depth <= 256 && !computing[index]) { AppText.get(R.string.model_err_accessor) }
            worlds[index]?.let { return it }
            computing[index] = true
            val world = if (parents[index] >= 0) multiply(restWorld(parents[index], depth + 1), local[index]) else local[index]
            computing[index] = false
            require(world.all(Float::isFinite)) { AppText.get(R.string.model_err_accessor) }
            worlds[index] = world
            return world
        }
        val jointSet = skins.flatMap { skin -> skin.optJSONArray("joints")?.let { j -> List(j.length()) { j.optInt(it, -1) } }.orEmpty() }.toSet()
        require(jointSet.all { it in nodes.indices }) { AppText.get(R.string.model_err_accessor) }
        val clips = animations.mapIndexedNotNull { clipIndex, animation ->
            val samplers = animation.optJSONArray("samplers").objects()
            val channels = animation.optJSONArray("channels").objects()
            require(channels.size <= 32768) { AppText.get(R.string.model_err_too_complex) }
            val parsed = channels.mapNotNull { channel ->
                val target = channel.optJSONObject("target") ?: return@mapNotNull null
                val path = when (target.optString("path")) { "translation" -> 0; "rotation" -> 1; "scale" -> 2; else -> return@mapNotNull null }
                val sampler = samplers.getOrNull(channel.optInt("sampler", -1)) ?: error(AppText.get(R.string.model_err_accessor))
                val node = target.optInt("node", -1)
                require(node in nodes.indices && !nodes[node].has("matrix")) { AppText.get(R.string.model_err_accessor) }
                val input = sampler.optInt("input", -1); val output = sampler.optInt("output", -1)
                charge(input); charge(output)
                val (times, tc) = read(input, normalizeInts = false) ?: error(AppText.get(R.string.model_err_accessor))
                val (values, vc) = read(output) ?: error(AppText.get(R.string.model_err_accessor))
                val interpolation = when (sampler.optString("interpolation", "LINEAR")) { "STEP" -> 0; "LINEAR" -> 1; "CUBICSPLINE" -> 2; else -> error(AppText.get(R.string.model_err_accessor)) }
                val components = if (path == 1) 4 else 3
                require(tc == 1 && times.isNotEmpty() && times[0] >= 0 && times.indices.drop(1).all { times[it] > times[it - 1] } &&
                    vc == components && values.size.toLong() == times.size.toLong() * components * (if (interpolation == 2) 3 else 1) &&
                    (interpolation != 2 || times.size >= 2)) { AppText.get(R.string.model_err_accessor) }
                ModelRigChannel(node, path, interpolation, times, values)
            }
            if (parsed.isEmpty()) null else ModelRigClip(animation.optString("name").ifBlank { "Animation ${clipIndex + 1}" }, parsed.maxOf { it.times.last() }, parsed)
        }
        val rig = if (skins.isNotEmpty() || clips.isNotEmpty()) RigBuild(nodes.mapIndexed { i, n ->
            fun values(name: String, fallback: FloatArray): FloatArray = n.optJSONArray(name)?.let { a ->
                require(a.length() == fallback.size) { AppText.get(R.string.model_err_accessor) }
                FloatArray(fallback.size) { a.getDouble(it).toFloat() }
            } ?: fallback
            ModelRigNode(ModelJoint(i, n.optString("name").ifBlank { "Node ${i + 1}" }, parents[i], i in jointSet), local[i],
                values("translation", floatArrayOf(0f,0f,0f)), values("rotation", floatArrayOf(0f,0f,0f,1f)),
                values("scale", floatArrayOf(1f,1f,1f)), n.has("matrix"))
        }, clips).also { builder.rig = it; nodes.indices.forEach { i -> restWorld(i) } } else null
        val skinBindings = HashMap<Int, IntArray>()
        val staticBindings = HashMap<Int, Int>()
        fun bindingsFor(index: Int): IntArray = skinBindings.getOrPut(index) {
            val skin = skins.getOrNull(index) ?: error(AppText.get(R.string.model_err_accessor))
            val joints = skin.getJSONArray("joints")
            require(joints.length() in 1..8192) { AppText.get(R.string.model_err_too_complex) }
            val matrices = if (skin.has("inverseBindMatrices")) read(skin.getInt("inverseBindMatrices"), false)?.takeIf { it.second == 16 && it.first.size >= joints.length() * 16 }?.first
                ?: error(AppText.get(R.string.model_err_accessor)) else null
            IntArray(joints.length()) { j ->
                val binding = ModelRigBinding(joints.getInt(j), matrices?.copyOfRange(j * 16, j * 16 + 16) ?: identity)
                require(binding.node in nodes.indices) { AppText.get(R.string.model_err_accessor) }
                requireNotNull(rig).bindings.add(binding)
                rig.bindings.lastIndex
            }
        }

        fun addMesh(meshIndex: Int, world: FloatArray, nodeIndex: Int = -1) {
            val normalMatrix = normalMatrix(world)
            val skinIndex = nodes.getOrNull(nodeIndex)?.optInt("skin", -1) ?: -1
            val skin = if (skinIndex >= 0) bindingsFor(skinIndex) else null
            val rigid = if (rig != null && skin == null) staticBindings.getOrPut(nodeIndex) {
                require(nodeIndex in nodes.indices) { AppText.get(R.string.model_err_accessor) }
                rig.bindings.add(ModelRigBinding(nodeIndex, identity)); rig.bindings.lastIndex
            } else -1
            val flip = skin == null && determinant(world) < 0
            for (primitive in meshes.getOrNull(meshIndex)?.optJSONArray("primitives").objects()) {
                val mode = primitive.optInt("mode", 4)
                if (mode !in 4..6) continue
                val attributes = primitive.optJSONObject("attributes") ?: continue
                charge(attributes.optInt("POSITION", -1))
                charge(primitive.optInt("indices", -1))
                val (positions, pc) = read(attributes.optInt("POSITION", -1)) ?: continue
                if (pc != 3) continue
                val count = positions.size / 3
                val normals = read(attributes.optInt("NORMAL", -1), expect = count)?.first?.takeIf { it.size == count * 3 }
                val uvs = read(attributes.optInt("TEXCOORD_0", -1), expect = count)?.first?.takeIf { it.size == count * 2 }
                val colours = read(attributes.optInt("COLOR_0", -1), expect = count)?.takeIf { it.first.size == count * it.second && it.second >= 3 }
                val material = materials.getOrNull(primitive.optInt("material", -1))
                twoSided = twoSided || material?.optBoolean("doubleSided") == true
                val pbr = material?.optJSONObject("pbrMetallicRoughness")
                val factor = pbr?.optJSONArray("baseColorFactor")?.let { f -> FloatArray(4) { f.optDouble(it, 1.0).toFloat() } } ?: floatArrayOf(1f, 1f, 1f, 1f)
                val slot = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1)?.let { textureList.getOrNull(it) }?.optInt("source", -1)
                    ?.takeIf { it >= 0 }?.let { src -> imageSlots.getOrPut(src) { image(src)?.let { textures += it; textures.size - 1 } ?: -1 } } ?: -1
                val list = indices(primitive, count)
                val jointArrays = if (skin != null) (0..1).map { read(attributes.optInt("JOINTS_$it", -1), false, count)?.takeIf { it.second == 4 }?.first } else emptyList()
                val weightArrays = if (skin != null) (0..1).map { read(attributes.optInt("WEIGHTS_$it", -1), true, count)?.takeIf { it.second == 4 }?.first } else emptyList()
                require(skin == null || (jointArrays[0] != null && weightArrays[0] != null && (jointArrays[1] == null) == (weightArrays[1] == null))) { AppText.get(R.string.model_err_accessor) }
                val rawNormals = normals ?: if (rig != null) generatedNormals(positions, list, mode) else null
                val skinMatrices = skin?.map { b -> val bind = requireNotNull(rig).bindings[b]; multiply(restWorld(bind.node), bind.inverseBind) }
                val skinNormals = skinMatrices?.map(::normalMatrix)
                builder.reserve(count, list.size)
                val first = builder.count
                for (v in 0 until count) {
                    val rawP = positions.copyOfRange(v * 3, v * 3 + 3)
                    val rawN = rawNormals?.copyOfRange(v * 3, v * 3 + 3)
                    val ids = IntArray(8) { -1 }; val localIds = IntArray(8); val weights = FloatArray(8)
                    if (skin != null) {
                        repeat(8) { k ->
                            val joint = jointArrays[k / 4]?.get(v * 4 + k % 4) ?: 0f
                            val weight = weightArrays[k / 4]?.get(v * 4 + k % 4) ?: 0f
                            require(weight >= 0f && joint >= 0f && joint == joint.toInt().toFloat() && joint < skin.size) { AppText.get(R.string.model_err_accessor) }
                            localIds[k] = joint.toInt(); ids[k] = skin[localIds[k]]; weights[k] = weight
                        }
                        val sum = weights.sum()
                        require(sum > 0f && sum.isFinite()) { AppText.get(R.string.model_err_accessor) }
                        repeat(8) { weights[it] /= sum }
                    } else if (rig != null) { ids[0] = rigid; weights[0] = 1f }
                    val p: FloatArray; val n: FloatArray?
                    if (skin != null) {
                        p = FloatArray(3); n = FloatArray(3)
                        repeat(8) { k -> if (weights[k] > 0f) {
                            val j = localIds[k]
                            val q = transform(requireNotNull(skinMatrices)[j], rawP[0], rawP[1], rawP[2])
                            val nn = requireNotNull(rawN).let { transformNormal(requireNotNull(skinNormals)[j], it[0], it[1], it[2]) }
                            repeat(3) { c -> p[c] += q[c] * weights[k]; n[c] += nn[c] * weights[k] }
                        } }
                    } else {
                        p = transform(world, rawP[0], rawP[1], rawP[2])
                        n = rawN?.let { transformNormal(normalMatrix, it[0], it[1], it[2]) }
                    }
                    val c = colours?.let { (values, comps) -> FloatArray(4) { i -> if (i < comps) values[v * comps + i] else 1f } } ?: floatArrayOf(1f, 1f, 1f, 1f)
                    builder.vertex(p[0], p[1], p[2], n, uvs?.get(v * 2) ?: 0f, uvs?.get(v * 2 + 1) ?: 0f,
                        argb(factor[0] * c[0], factor[1] * c[1], factor[2] * c[2], factor[3] * c[3]),
                        if (rig != null) RigVertex(rawP, requireNotNull(rawN), ids, weights) else null)
                }
                fun emit(a: Int, b: Int, c: Int) {
                    if (a !in 0 until count || b !in 0 until count || c !in 0 until count) return
                    if (flip) builder.triangle(first + a, first + c, first + b, slot) else builder.triangle(first + a, first + b, first + c, slot)
                }
                when (mode) {
                    4 -> for (k in 0 until list.size - 2 step 3) emit(list[k], list[k + 1], list[k + 2])
                    5 -> for (k in 0 until list.size - 2) {
                        if (k % 2 == 0) emit(list[k], list[k + 1], list[k + 2]) else emit(list[k + 1], list[k], list[k + 2])
                    }
                    else -> for (k in 1 until list.size - 1) emit(list[0], list[k], list[k + 1])
                }
            }
        }

        val visited = BooleanArray(nodes.size)
        fun visit(index: Int, parent: FloatArray, depth: Int) {
            val node = nodes.getOrNull(index) ?: return
            if (depth > 64 || visited[index]) return
            visited[index] = true
            val world = multiply(parent, localMatrix(node))
            if (node.has("mesh")) addMesh(node.getInt("mesh"), world, index)
            node.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) visit(c.optInt(i, -1), world, depth + 1) }
        }

        if (nodes.isEmpty()) meshes.indices.forEach { addMesh(it, identity) }
        else {
            val scenes = json.optJSONArray("scenes").objects()
            val children = nodes.flatMap { n -> n.optJSONArray("children")?.let { c -> List(c.length()) { c.optInt(it, -1) } }.orEmpty() }.toSet()
            val roots = scenes.getOrNull(json.optInt("scene", 0))?.optJSONArray("nodes")?.let { r -> List(r.length()) { r.optInt(it, -1) } }
                ?: nodes.indices.filter { it !in children }
            roots.forEach { visit(it, identity, 0) }
        }
        return builder.build(textures, twoSided)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }

    private fun generatedNormals(p: FloatArray, indices: IntArray, mode: Int): FloatArray {
        val out = FloatArray(p.size)
        fun face(a: Int, b: Int, c: Int) {
            if (a !in 0 until p.size / 3 || b !in 0 until p.size / 3 || c !in 0 until p.size / 3) return
            val ax = p[b*3]-p[a*3]; val ay = p[b*3+1]-p[a*3+1]; val az = p[b*3+2]-p[a*3+2]
            val bx = p[c*3]-p[a*3]; val by = p[c*3+1]-p[a*3+1]; val bz = p[c*3+2]-p[a*3+2]
            val n = floatArrayOf(ay*bz-az*by, az*bx-ax*bz, ax*by-ay*bx)
            for (v in intArrayOf(a,b,c)) repeat(3) { out[v*3+it] += n[it] }
        }
        when (mode) {
            4 -> for (k in 0 until indices.size-2 step 3) face(indices[k],indices[k+1],indices[k+2])
            5 -> for (k in 0 until indices.size-2) if (k%2 == 0) face(indices[k],indices[k+1],indices[k+2]) else face(indices[k+1],indices[k],indices[k+2])
            else -> for (k in 1 until indices.size-1) face(indices[0],indices[k],indices[k+1])
        }
        for (v in 0 until p.size/3) {
            val length = sqrt(out[v*3]*out[v*3]+out[v*3+1]*out[v*3+1]+out[v*3+2]*out[v*3+2])
            if (length > 0f) repeat(3) { out[v*3+it] /= length } else out[v*3+2] = 1f
        }
        return out
    }

    private fun le(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun load(uri: String, open: (String) -> ByteArray?): ByteArray? =
        if (uri.startsWith("data:")) runCatching { Base64.getDecoder().decode(uri.substringAfter(',')) }.getOrNull()
        else open(runCatching { java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri))

    private fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.takeIf { it.length() == 16 }?.let { m -> return FloatArray(16) { m.optDouble(it, 0.0).toFloat() } }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val x = r?.optDouble(0, 0.0)?.toFloat() ?: 0f; val y = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val z = r?.optDouble(2, 0.0)?.toFloat() ?: 0f; val w = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f; val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f; val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f
        return floatArrayOf(
            (1 - 2 * (y * y + z * z)) * sx, (2 * (x * y + z * w)) * sx, (2 * (x * z - y * w)) * sx, 0f,
            (2 * (x * y - z * w)) * sy, (1 - 2 * (x * x + z * z)) * sy, (2 * (y * z + x * w)) * sy, 0f,
            (2 * (x * z + y * w)) * sz, (2 * (y * z - x * w)) * sz, (1 - 2 * (x * x + y * y)) * sz, 0f,
            t?.optDouble(0, 0.0)?.toFloat() ?: 0f, t?.optDouble(1, 0.0)?.toFloat() ?: 0f, t?.optDouble(2, 0.0)?.toFloat() ?: 0f, 1f,
        )
    }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val col = i / 4; val row = i % 4
        (0..3).sumOf { k -> (a[k * 4 + row] * b[col * 4 + k]).toDouble() }.toFloat()
    }

    private fun transform(m: FloatArray, x: Float, y: Float, z: Float) =
        floatArrayOf(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])

    private fun determinant(m: FloatArray) =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    private fun normalMatrix(m: FloatArray): FloatArray {
        val s = if (determinant(m) < 0) -1f else 1f
        return floatArrayOf(
            m[5] * m[10] - m[6] * m[9], m[6] * m[8] - m[4] * m[10], m[4] * m[9] - m[5] * m[8],
            m[2] * m[9] - m[1] * m[10], m[0] * m[10] - m[2] * m[8], m[1] * m[8] - m[0] * m[9],
            m[1] * m[6] - m[2] * m[5], m[2] * m[4] - m[0] * m[6], m[0] * m[5] - m[1] * m[4],
        ).also { c -> for (i in c.indices) c[i] *= s }
    }

    private fun transformNormal(c: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val n = floatArrayOf(c[0] * x + c[3] * y + c[6] * z, c[1] * x + c[4] * y + c[7] * z, c[2] * x + c[5] * y + c[8] * z)
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        return if (l > 0f) floatArrayOf(n[0] / l, n[1] / l, n[2] / l) else floatArrayOf(0f, 0f, 1f)
    }
}

fun decimated(mesh: Mesh, budget: Int): Mesh {
    if (mesh.triangleCount <= budget) return mesh
    // Do not merge vertices with different skin weights into an unanimated mesh.
    // Retain a uniformly distributed set of original faces and all their attributes.
    if (mesh.rig != null) return reducedRig(mesh, budget.coerceAtLeast(1))
    var best: Mesh? = null
    var low = 0
    var high = 4097
    var cells = 256
    repeat(12) {
        val result = clustered(mesh, cells)
        val count = result.triangleCount
        if (count <= budget) {
            if (best.let { it == null || count > it.triangleCount }) best = result
            if (count >= budget * 0.85) return result
            low = cells
        } else high = cells
        if (high - low <= 1) return best ?: clustered(mesh, 4)
        val guess = (cells * sqrt(budget * 0.95 / max(1, count))).toInt()
        cells = if (guess in low + 1 until high) guess else (low + high) / 2
    }
    return best ?: clustered(mesh, 4)
}

private fun reducedRig(mesh: Mesh, budget: Int): Mesh {
    val remap = IntArray(mesh.vertexCount) { -1 }
    val keep = ArrayList<Int>()
    val slots = IntArray(budget)
    val faces = IntArray(budget * 3)
    repeat(budget) { t ->
        val source = (t.toLong() * mesh.triangleCount / budget).toInt()
        slots[t] = mesh.slots[source]
        repeat(3) { k ->
            val v = mesh.triangles[source*3+k]
            if (remap[v] < 0) { remap[v] = keep.size; keep.add(v) }
            faces[t*3+k] = remap[v]
        }
    }
    fun floats(values: FloatArray, size: Int) = FloatArray(keep.size*size) { values[keep[it/size]*size+it%size] }
    return Mesh(floats(mesh.positions,3),floats(mesh.normals,3),floats(mesh.uvs,2),IntArray(keep.size) { mesh.colors[keep[it]] },
        faces,slots,mesh.textures,mesh.twoSided,mesh.rig!!.subset(keep.toIntArray()))
}

private const val UV_CELLS = 512

private fun clustered(mesh: Mesh, cells: Int): Mesh {
    val p = mesh.positions
    val group = IntArray(mesh.vertexCount)
    val groups = HashMap<Long, Int>()
    fun cell(v: Float) = ((v + 1f) / 2f * cells).toInt().coerceIn(0, cells - 1).toLong()
    for (v in 0 until mesh.vertexCount) {
        group[v] = groups.getOrPut((cell(p[v * 3]) * cells + cell(p[v * 3 + 1])) * cells + cell(p[v * 3 + 2])) { groups.size }
    }
    val centres = DoubleArray(groups.size * 3)
    val members = IntArray(groups.size)
    for (v in 0 until mesh.vertexCount) {
        members[group[v]]++
        for (i in 0..2) centres[group[v] * 3 + i] += p[v * 3 + i].toDouble()
    }
    val corners = Ints()
    val slots = Ints()
    val seen = HashSet<Triple<Int, Int, Int>>()
    val t = mesh.triangles
    for (k in 0 until mesh.triangleCount) {
        val a = group[t[k * 3]]; val b = group[t[k * 3 + 1]]; val c = group[t[k * 3 + 2]]
        if (a == b || b == c || a == c) continue
        val first = min(a, min(b, c))
        val key = when (first) { a -> Triple(a, b, c); b -> Triple(b, c, a); else -> Triple(c, a, b) }
        if (!seen.add(key)) continue
        for (i in 0..2) corners.add(t[k * 3 + i])
        slots.add(mesh.slots[k])
    }
    val output = HashMap<Pair<Long, Int>, Int>()
    fun facing(v: Int) = (0..2).fold(0) { bins, i -> bins * 5 + (kotlin.math.round(mesh.normals[v * 3 + i] * 2f).toInt().coerceIn(-2, 2) + 2) }
    val outGroup = Ints()
    val sums = ArrayList<DoubleArray>()
    val triangles = IntArray(corners.size)
    fun quantised(v: Float) = (kotlin.math.floor(v.toDouble() * UV_CELLS).toLong() and 0xffff)
    for (k in 0 until corners.size) {
        val v = corners.data[k]
        val key = (group[v].toLong() shl 40) or (((slots.data[k / 3] + 1).toLong() and 0xff) shl 32) or
            (quantised(mesh.uvs[v * 2]) shl 16) or quantised(mesh.uvs[v * 2 + 1])
        val out = output.getOrPut(key to facing(v)) { outGroup.add(group[v]); sums.add(DoubleArray(10)); sums.size - 1 }
        triangles[k] = out
        val s = sums[out]
        for (i in 0..2) s[i] += mesh.normals[v * 3 + i].toDouble()
        s[3] += mesh.uvs[v * 2].toDouble(); s[4] += mesh.uvs[v * 2 + 1].toDouble()
        val colour = mesh.colors[v]
        for (i in 0..3) s[5 + i] += ((colour ushr (24 - 8 * i)) and 0xff).toDouble()
        s[9] += 1.0
    }
    val n = sums.size
    val positions = FloatArray(n * 3) { centres[outGroup.data[it / 3] * 3 + it % 3].toFloat() / members[outGroup.data[it / 3]] }
    val normals = FloatArray(n * 3)
    for (o in 0 until n) {
        val s = sums[o]
        val length = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        if (length > 0) for (i in 0..2) normals[o * 3 + i] = (s[i] / length).toFloat()
    }
    val uvs = FloatArray(n * 2) { (sums[it / 2][3 + it % 2] / sums[it / 2][9]).toFloat() }
    val colors = IntArray(n) { o -> (0..3).fold(0) { c, i -> (c shl 8) or (sums[o][5 + i] / sums[o][9] + 0.5).toInt().coerceIn(0, 255) } }
    return Mesh(positions, normals, uvs, colors, triangles, slots.array(), mesh.textures, mesh.twoSided)
}
