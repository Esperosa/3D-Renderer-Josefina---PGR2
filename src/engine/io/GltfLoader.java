package engine.io;

import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.material.TextureMap;
import engine.math.Mat3;
import engine.math.Mat4;
import engine.math.Quaternion;
import engine.math.Vec3;
import engine.render.Texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents minimální načítač glTF 2.0 pro soubory .gltf a .glb.
 * Importuju s ním trojúhelníkové primitivy a transformace scénových uzlů.
 */
public class GltfLoader {

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_CHUNK_JSON = 0x4E4F534A;
    private static final int GLB_CHUNK_BIN = 0x004E4942;

    private static final int COMPONENT_BYTE = 5120;
    private static final int COMPONENT_UBYTE = 5121;
    private static final int COMPONENT_SHORT = 5122;
    private static final int COMPONENT_USHORT = 5123;
    private static final int COMPONENT_UINT = 5125;
    private static final int COMPONENT_FLOAT = 5126;

    private static final class BufferViewDef {
        int buffer;
        int byteOffset;
        int byteLength;
        int byteStride;
    }

    private static final class AccessorDef {
        int bufferView;
        int byteOffset;
        int componentType;
        int count;
        String type;
        boolean normalized;
    }

    private static final class PrimitiveDef {
        int positionAccessor;
        int normalAccessor;
        int uvAccessor;
        int uv2Accessor;
        int indicesAccessor;
        int materialIndex;
    }

    private static final class NodeDef {
        String name;
        int meshIndex;
        int[] children;
        Mat4 localMatrix;
    }

    private static final class ImageDef {
        String uri;
        int bufferView;
    }

    private static final class TextureDef {
        int source;
        int sampler;
    }

    private static final class SamplerDef {
        int magFilter;
        int minFilter;
    }

    private static final class TextureBinding {
        Texture texture;
        boolean linear;
    }

    private static final class PrimitiveBuild {
        Mesh mesh;
        PhongMaterial material;
    }

    public ImportedScene load(String filePath) {
        Path source = Path.of(filePath).toAbsolutePath().normalize();
        String ext = FileUtil.getExtension(filePath);

        byte[] bytes = FileUtil.readBytes(source.toString());
        String jsonText;
        byte[] binChunk = null;
        if ("glb".equals(ext)) {
            GlbData glb = parseGlb(bytes, source.toString());
            jsonText = glb.json;
            binChunk = glb.binChunk;
        } else if ("gltf".equals(ext)) {
            jsonText = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new RuntimeException("Unsupported glTF extension: ." + ext);
        }

        Map<String, Object> root = asObject(new SimpleJsonParser(jsonText).parse(), "root");
        List<Map<String, Object>> buffersJson = asObjectList(root.get("buffers"));
        List<Map<String, Object>> viewsJson = asObjectList(root.get("bufferViews"));
        List<Map<String, Object>> accessorsJson = asObjectList(root.get("accessors"));
        List<Map<String, Object>> meshesJson = asObjectList(root.get("meshes"));
        List<Map<String, Object>> nodesJson = asObjectList(root.get("nodes"));
        List<Map<String, Object>> scenesJson = asObjectList(root.get("scenes"));
        List<Map<String, Object>> materialsJson = asObjectList(root.get("materials"));
        List<Map<String, Object>> texturesJson = asObjectList(root.get("textures"));
        List<Map<String, Object>> imagesJson = asObjectList(root.get("images"));
        List<Map<String, Object>> samplersJson = asObjectList(root.get("samplers"));

        if (buffersJson.isEmpty() || viewsJson.isEmpty() || accessorsJson.isEmpty() || meshesJson.isEmpty()) {
            throw new RuntimeException("glTF has no mesh data.");
        }

        List<byte[]> buffers = loadBuffers(buffersJson, source.getParent(), binChunk);
        List<BufferViewDef> bufferViews = parseBufferViews(viewsJson);
        List<AccessorDef> accessors = parseAccessors(accessorsJson);
        List<List<PrimitiveDef>> meshes = parseMeshes(meshesJson);
        List<NodeDef> nodes = parseNodes(nodesJson);
        List<TextureBinding> textures = loadTextureBindings(
                source.getParent(),
                imagesJson,
                texturesJson,
                samplersJson,
                bufferViews,
                buffers
        );
        List<PhongMaterial> primitiveMaterials = parseMaterials(materialsJson, textures);

        int defaultSceneIndex = intOrDefault(root.get("scene"), scenesJson.isEmpty() ? -1 : 0);
        int[] roots = resolveRootNodes(scenesJson, nodes.size(), defaultSceneIndex);

        ImportedScene imported = new ImportedScene();
        imported.setSourcePath(source.toString());

        Map<String, PrimitiveBuild> meshCache = new HashMap<>();
        Set<Integer> recursionGuard = new HashSet<>();
        String fileBase = baseName(source);
        for (int rootNode : roots) {
            traverseNode(
                    rootNode,
                    Mat4.identity(),
                    nodes,
                    meshes,
                    accessors,
                    bufferViews,
                    buffers,
                    primitiveMaterials,
                    meshCache,
                    recursionGuard,
                    imported,
                    fileBase
            );
        }
        if (imported.isEmpty()) {
            throw new RuntimeException("No triangle primitives found in " + filePath);
        }
        return imported;
    }

    private void traverseNode(
            int nodeIndex,
            Mat4 parentWorld,
            List<NodeDef> nodes,
            List<List<PrimitiveDef>> meshes,
            List<AccessorDef> accessors,
            List<BufferViewDef> bufferViews,
            List<byte[]> buffers,
            List<PhongMaterial> primitiveMaterials,
            Map<String, PrimitiveBuild> meshCache,
            Set<Integer> recursionGuard,
            ImportedScene out,
            String fileBase) {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            return;
        }
        if (!recursionGuard.add(nodeIndex)) {
            return;
        }
        NodeDef node = nodes.get(nodeIndex);
        Mat4 world = parentWorld.multiply(node.localMatrix);
        String baseNodeName = (node.name == null || node.name.isBlank())
                ? fileBase + "_node_" + nodeIndex
                : node.name;

        if (node.meshIndex >= 0 && node.meshIndex < meshes.size()) {
            List<PrimitiveDef> primitives = meshes.get(node.meshIndex);
            for (int i = 0; i < primitives.size(); i++) {
                PrimitiveDef prim = primitives.get(i);
                String key = node.meshIndex + ":" + i;
                PrimitiveBuild local = meshCache.get(key);
                if (local == null) {
                    local = buildPrimitive(key, prim, accessors, bufferViews, buffers, primitiveMaterials);
                    meshCache.put(key, local);
                }
                if (local != null && local.mesh != null) {
                    Mesh worldMesh = isIdentity(world) ? local.mesh : transformMesh(local.mesh, world, baseNodeName + "_p" + i);
                    String name = primitives.size() > 1 ? baseNodeName + "_p" + i : baseNodeName;
                    out.addEntry(name, worldMesh, cloneMaterial(local.material));
                }
            }
        }

        for (int child : node.children) {
            traverseNode(
                    child,
                    world,
                    nodes,
                    meshes,
                    accessors,
                    bufferViews,
                    buffers,
                    primitiveMaterials,
                    meshCache,
                    recursionGuard,
                    out,
                    fileBase
            );
        }
        recursionGuard.remove(nodeIndex);
    }

    private PrimitiveBuild buildPrimitive(
            String name,
            PrimitiveDef prim,
            List<AccessorDef> accessors,
            List<BufferViewDef> bufferViews,
            List<byte[]> buffers,
            List<PhongMaterial> primitiveMaterials) {
        if (prim.positionAccessor < 0 || prim.positionAccessor >= accessors.size()) {
            return null;
        }
        AccessorDef posAcc = accessors.get(prim.positionAccessor);
        int posComps = typeComponentCount(posAcc.type);
        if (posComps < 3) {
            return null;
        }

        float[] posRaw = readAccessorFloats(posAcc, bufferViews, buffers);
        int vertexCount = posAcc.count;
        float[] positions = new float[vertexCount * 3];
        for (int i = 0; i < vertexCount; i++) {
            int src = i * posComps;
            int dst = i * 3;
            positions[dst] = posRaw[src];
            positions[dst + 1] = posRaw[src + 1];
            positions[dst + 2] = posRaw[src + 2];
        }

        float[] normals = null;
        if (prim.normalAccessor >= 0 && prim.normalAccessor < accessors.size()) {
            AccessorDef nAcc = accessors.get(prim.normalAccessor);
            int nComps = typeComponentCount(nAcc.type);
            if (nComps >= 3 && nAcc.count == vertexCount) {
                float[] nRaw = readAccessorFloats(nAcc, bufferViews, buffers);
                normals = new float[vertexCount * 3];
                for (int i = 0; i < vertexCount; i++) {
                    int src = i * nComps;
                    int dst = i * 3;
                    normals[dst] = nRaw[src];
                    normals[dst + 1] = nRaw[src + 1];
                    normals[dst + 2] = nRaw[src + 2];
                }
            }
        }

        float[] uvs = null;
        if (prim.uvAccessor >= 0 && prim.uvAccessor < accessors.size()) {
            AccessorDef uvAcc = accessors.get(prim.uvAccessor);
            int uvComps = typeComponentCount(uvAcc.type);
            if (uvComps >= 2 && uvAcc.count == vertexCount) {
                float[] uvRaw = readAccessorFloats(uvAcc, bufferViews, buffers);
                uvs = new float[vertexCount * 2];
                for (int i = 0; i < vertexCount; i++) {
                    int src = i * uvComps;
                    int dst = i * 2;
                    uvs[dst] = uvRaw[src];
                    uvs[dst + 1] = uvRaw[src + 1];
                }
            }
        }

        float[] uv2s = null;
        if (prim.uv2Accessor >= 0 && prim.uv2Accessor < accessors.size()) {
            AccessorDef uvAcc = accessors.get(prim.uv2Accessor);
            int uvComps = typeComponentCount(uvAcc.type);
            if (uvComps >= 2 && uvAcc.count == vertexCount) {
                float[] uvRaw = readAccessorFloats(uvAcc, bufferViews, buffers);
                uv2s = new float[vertexCount * 2];
                for (int i = 0; i < vertexCount; i++) {
                    int src = i * uvComps;
                    int dst = i * 2;
                    uv2s[dst] = uvRaw[src];
                    uv2s[dst + 1] = uvRaw[src + 1];
                }
            }
        }

        int[] indices;
        if (prim.indicesAccessor >= 0 && prim.indicesAccessor < accessors.size()) {
            int[] raw = readAccessorIndices(accessors.get(prim.indicesAccessor), bufferViews, buffers);
            int valid = (raw.length / 3) * 3;
            if (valid < 3) {
                return null;
            }
            if (valid == raw.length) {
                indices = raw;
            } else {
                indices = new int[valid];
                System.arraycopy(raw, 0, indices, 0, valid);
            }
        } else {
            int valid = (vertexCount / 3) * 3;
            if (valid < 3) {
                return null;
            }
            indices = new int[valid];
            for (int i = 0; i < valid; i++) {
                indices[i] = i;
            }
        }

        Mesh mesh = new Mesh(name, positions, normals, indices);
        if (uvs != null) {
            mesh.setUVs(uvs);
        }
        if (uv2s != null) {
            mesh.setUV2s(uv2s);
        }
        if (normals == null) {
            mesh.computeNormals();
        }
        mesh.computeBounds();
        PrimitiveBuild out = new PrimitiveBuild();
        out.mesh = mesh;
        out.material = materialForPrimitive(prim.materialIndex, primitiveMaterials);
        return out;
    }

    private Mesh transformMesh(Mesh source, Mat4 transform, String name) {
        float[] srcPos = source.getPositions();
        float[] srcNrm = source.getNormals();
        int[] srcIdx = source.getIndices();

        float[] dstPos = new float[srcPos.length];
        float[] dstNrm = srcNrm == null ? null : new float[srcNrm.length];
        int[] dstIdx = new int[srcIdx.length];
        System.arraycopy(srcIdx, 0, dstIdx, 0, srcIdx.length);

        Mat3 normalMat;
        try {
            normalMat = transform.inverse().transpose().toMat3();
        } catch (IllegalStateException ex) {
            normalMat = Mat3.identity();
        }

        for (int i = 0; i < srcPos.length; i += 3) {
            Vec3 p = new Vec3(srcPos[i], srcPos[i + 1], srcPos[i + 2]);
            Vec3 tp = transform.transformPoint(p);
            dstPos[i] = (float) tp.x;
            dstPos[i + 1] = (float) tp.y;
            dstPos[i + 2] = (float) tp.z;
        }
        if (dstNrm != null) {
            for (int i = 0; i < srcNrm.length; i += 3) {
                Vec3 n = new Vec3(srcNrm[i], srcNrm[i + 1], srcNrm[i + 2]);
                Vec3 tn = normalMat.transform(n).normalize();
                dstNrm[i] = (float) tn.x;
                dstNrm[i + 1] = (float) tn.y;
                dstNrm[i + 2] = (float) tn.z;
            }
        }

        Mesh mesh = new Mesh(name, dstPos, dstNrm, dstIdx);
        if (source.getUVs() != null) {
            float[] uvSrc = source.getUVs();
            float[] uvCopy = new float[uvSrc.length];
            System.arraycopy(uvSrc, 0, uvCopy, 0, uvSrc.length);
            mesh.setUVs(uvCopy);
        }
        if (source.getUV2s() != null) {
            float[] uvSrc = source.getUV2s();
            float[] uvCopy = new float[uvSrc.length];
            System.arraycopy(uvSrc, 0, uvCopy, 0, uvSrc.length);
            mesh.setUV2s(uvCopy);
        }
        if (source.getTangents() != null) {
            float[] tangentSrc = source.getTangents();
            float[] tangentCopy = new float[tangentSrc.length];
            System.arraycopy(tangentSrc, 0, tangentCopy, 0, tangentSrc.length);
            mesh.setTangents(tangentCopy);
        }
        mesh.computeBounds();
        return mesh;
    }

    private List<byte[]> loadBuffers(List<Map<String, Object>> defs, Path baseDir, byte[] glbBinChunk) {
        List<byte[]> out = new ArrayList<>(defs.size());
        for (int i = 0; i < defs.size(); i++) {
            Map<String, Object> def = defs.get(i);
            String uri = stringOrNull(def.get("uri"));
            int expected = intOrDefault(def.get("byteLength"), -1);
            byte[] data;
            if (uri == null || uri.isBlank()) {
                if (i == 0 && glbBinChunk != null) {
                    data = glbBinChunk;
                } else {
                    throw new RuntimeException("Buffer " + i + " missing URI/bin chunk.");
                }
            } else if (uri.startsWith("data:")) {
                data = decodeDataUri(uri);
            } else {
                Path path = baseDir == null ? Path.of(uri) : baseDir.resolve(uri).normalize();
                if (!Files.exists(path)) {
                    throw new RuntimeException("Missing external buffer: " + path);
                }
                data = FileUtil.readBytes(path.toString());
            }
            if (expected > 0 && data.length < expected) {
                throw new RuntimeException("Buffer shorter than declared byteLength.");
            }
            out.add(data);
        }
        return out;
    }

    private List<BufferViewDef> parseBufferViews(List<Map<String, Object>> defs) {
        List<BufferViewDef> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            BufferViewDef view = new BufferViewDef();
            view.buffer = intOrDefault(def.get("buffer"), 0);
            view.byteOffset = intOrDefault(def.get("byteOffset"), 0);
            view.byteLength = intOrDefault(def.get("byteLength"), 0);
            view.byteStride = intOrDefault(def.get("byteStride"), 0);
            out.add(view);
        }
        return out;
    }

    private List<AccessorDef> parseAccessors(List<Map<String, Object>> defs) {
        List<AccessorDef> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            AccessorDef a = new AccessorDef();
            a.bufferView = intOrDefault(def.get("bufferView"), -1);
            a.byteOffset = intOrDefault(def.get("byteOffset"), 0);
            a.componentType = intOrDefault(def.get("componentType"), COMPONENT_FLOAT);
            a.count = intOrDefault(def.get("count"), 0);
            a.type = stringOrDefault(def.get("type"), "SCALAR");
            a.normalized = booleanOrDefault(def.get("normalized"), false);
            out.add(a);
        }
        return out;
    }

    private List<List<PrimitiveDef>> parseMeshes(List<Map<String, Object>> defs) {
        List<List<PrimitiveDef>> out = new ArrayList<>(defs.size());
        for (Map<String, Object> mesh : defs) {
            List<Map<String, Object>> primitives = asObjectList(mesh.get("primitives"));
            List<PrimitiveDef> primList = new ArrayList<>();
            for (Map<String, Object> primObj : primitives) {
                int mode = intOrDefault(primObj.get("mode"), 4);
                if (mode != 4) {
                    continue;
                }
                Map<String, Object> attrs = asObject(primObj.get("attributes"), "primitive.attributes");
                PrimitiveDef prim = new PrimitiveDef();
                prim.positionAccessor = intOrDefault(attrs.get("POSITION"), -1);
                prim.normalAccessor = intOrDefault(attrs.get("NORMAL"), -1);
                prim.uvAccessor = intOrDefault(attrs.get("TEXCOORD_0"), -1);
                prim.uv2Accessor = intOrDefault(attrs.get("TEXCOORD_1"), -1);
                prim.indicesAccessor = intOrDefault(primObj.get("indices"), -1);
                prim.materialIndex = intOrDefault(primObj.get("material"), -1);
                if (prim.positionAccessor >= 0) {
                    primList.add(prim);
                }
            }
            out.add(primList);
        }
        return out;
    }

    private List<NodeDef> parseNodes(List<Map<String, Object>> defs) {
        List<NodeDef> out = new ArrayList<>(defs.size());
        for (Map<String, Object> node : defs) {
            NodeDef def = new NodeDef();
            def.name = stringOrNull(node.get("name"));
            def.meshIndex = intOrDefault(node.get("mesh"), -1);
            List<Object> children = asList(node.get("children"));
            def.children = new int[children.size()];
            for (int i = 0; i < children.size(); i++) {
                def.children[i] = intOrDefault(children.get(i), -1);
            }
            def.localMatrix = parseNodeLocalMatrix(node);
            out.add(def);
        }
        return out;
    }

    private List<TextureBinding> loadTextureBindings(Path baseDir,
                                                     List<Map<String, Object>> imagesJson,
                                                     List<Map<String, Object>> texturesJson,
                                                     List<Map<String, Object>> samplersJson,
                                                     List<BufferViewDef> bufferViews,
                                                     List<byte[]> buffers) {
        List<ImageDef> images = parseImages(imagesJson);
        List<TextureDef> textures = parseTextureDefs(texturesJson);
        List<SamplerDef> samplers = parseSamplers(samplersJson);
        List<TextureBinding> out = new ArrayList<>(textures.size());
        for (TextureDef textureDef : textures) {
            TextureBinding binding = new TextureBinding();
            if (textureDef == null || textureDef.source < 0 || textureDef.source >= images.size()) {
                out.add(binding);
                continue;
            }
            ImageDef imageDef = images.get(textureDef.source);
            byte[] bytes = loadImageBytes(imageDef, baseDir, bufferViews, buffers);
            if (bytes == null || bytes.length == 0) {
                out.add(binding);
                continue;
            }
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) {
                    int w = image.getWidth();
                    int h = image.getHeight();
                    int[] pixels = new int[w * h];
                    image.getRGB(0, 0, w, h, pixels, 0, w);
                    binding.texture = new Texture(w, h, pixels);
                }
            } catch (Exception ignored) {
                binding.texture = null;
            }
            binding.linear = samplerLinear(textureDef.sampler, samplers);
            out.add(binding);
        }
        return out;
    }

    private List<ImageDef> parseImages(List<Map<String, Object>> defs) {
        List<ImageDef> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            ImageDef image = new ImageDef();
            image.uri = stringOrNull(def.get("uri"));
            image.bufferView = intOrDefault(def.get("bufferView"), -1);
            out.add(image);
        }
        return out;
    }

    private List<TextureDef> parseTextureDefs(List<Map<String, Object>> defs) {
        List<TextureDef> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            TextureDef texture = new TextureDef();
            texture.source = intOrDefault(def.get("source"), -1);
            texture.sampler = intOrDefault(def.get("sampler"), -1);
            out.add(texture);
        }
        return out;
    }

    private List<SamplerDef> parseSamplers(List<Map<String, Object>> defs) {
        List<SamplerDef> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            SamplerDef sampler = new SamplerDef();
            sampler.magFilter = intOrDefault(def.get("magFilter"), 9729);
            sampler.minFilter = intOrDefault(def.get("minFilter"), 9987);
            out.add(sampler);
        }
        return out;
    }

    private boolean samplerLinear(int samplerIndex, List<SamplerDef> samplers) {
        if (samplerIndex < 0 || samplerIndex >= samplers.size()) {
            return true;
        }
        SamplerDef sampler = samplers.get(samplerIndex);
        if (sampler == null) {
            return true;
        }
        boolean magNearest = sampler.magFilter == 9728;
        boolean minNearest = sampler.minFilter == 9728 || sampler.minFilter == 9984 || sampler.minFilter == 9986;
        return !(magNearest && minNearest);
    }

    private byte[] loadImageBytes(ImageDef imageDef,
                                  Path baseDir,
                                  List<BufferViewDef> bufferViews,
                                  List<byte[]> buffers) {
        if (imageDef == null) {
            return null;
        }
        if (imageDef.uri != null && !imageDef.uri.isBlank()) {
            if (imageDef.uri.startsWith("data:")) {
                return decodeDataUri(imageDef.uri);
            }
            Path path = baseDir == null ? Path.of(imageDef.uri) : baseDir.resolve(imageDef.uri).normalize();
            if (!Files.exists(path)) {
                return null;
            }
            return FileUtil.readBytes(path.toString());
        }
        if (imageDef.bufferView >= 0 && imageDef.bufferView < bufferViews.size()) {
            BufferViewDef view = bufferViews.get(imageDef.bufferView);
            if (view.buffer < 0 || view.buffer >= buffers.size()) {
                return null;
            }
            byte[] src = buffers.get(view.buffer);
            int start = Math.max(0, view.byteOffset);
            int declaredLen = Math.max(0, view.byteLength);
            int end = declaredLen > 0 ? Math.min(src.length, start + declaredLen) : src.length;
            if (start >= end || start >= src.length) {
                return null;
            }
            int len = Math.max(0, end - start);
            byte[] out = new byte[len];
            System.arraycopy(src, start, out, 0, len);
            return out;
        }
        return null;
    }

    private List<PhongMaterial> parseMaterials(List<Map<String, Object>> defs, List<TextureBinding> textures) {
        List<PhongMaterial> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            String name = stringOrDefault(def.get("name"), "GLTFMaterial");
            Vec3 baseColor = new Vec3(0.8, 0.8, 0.8);
            double opacity = 1.0;
            double metallic = 0.0;
            double roughness = 1.0;
            double transmission = 0.0;
            double ior = 1.0;
            Vec3 emissionColor = Vec3.ZERO;
            double emissionStrength = 0.0;
            double clearcoatFactor = 0.0;
            double clearcoatRoughness = 0.0;
            double specularFactor = 1.0;
            Vec3 specularColorFactor = Vec3.ONE;
            Vec3 sheenColor = Vec3.ZERO;
            double sheenRoughness = 0.0;
            boolean doubleSided = booleanOrDefault(def.get("doubleSided"), false);
            PhongMaterial.AlphaMode alphaMode = parseAlphaMode(stringOrDefault(def.get("alphaMode"), "OPAQUE"));
            double alphaCutoff = clamp01(toDouble(def.get("alphaCutoff"), 0.5));

            Map<String, Object> pbr = asObjectOrNull(def.get("pbrMetallicRoughness"));
            if (pbr != null) {
                List<Object> factor = asList(pbr.get("baseColorFactor"));
                if (factor.size() >= 3) {
                    baseColor = new Vec3(
                            toDouble(factor.get(0), baseColor.x),
                            toDouble(factor.get(1), baseColor.y),
                            toDouble(factor.get(2), baseColor.z)
                    );
                    if (factor.size() > 3) {
                        opacity = clamp01(toDouble(factor.get(3), opacity));
                    }
                }
                metallic = clamp01(toDouble(pbr.get("metallicFactor"), metallic));
                roughness = clamp01(toDouble(pbr.get("roughnessFactor"), roughness));
            }
            List<Object> emissiveFactor = asList(def.get("emissiveFactor"));
            if (emissiveFactor.size() >= 3) {
                emissionColor = new Vec3(
                        clamp01(toDouble(emissiveFactor.get(0), 0.0)),
                        clamp01(toDouble(emissiveFactor.get(1), 0.0)),
                        clamp01(toDouble(emissiveFactor.get(2), 0.0))
                );
                emissionStrength = Math.max(emissionColor.x, Math.max(emissionColor.y, emissionColor.z));
            }

            Map<String, Object> extensions = asObjectOrNull(def.get("extensions"));
            if (extensions != null) {
                Map<String, Object> transmissionExt = asObjectOrNull(extensions.get("KHR_materials_transmission"));
                if (transmissionExt != null) {
                    transmission = clamp01(toDouble(transmissionExt.get("transmissionFactor"), transmission));
                }
                Map<String, Object> iorExt = asObjectOrNull(extensions.get("KHR_materials_ior"));
                if (iorExt != null) {
                    ior = Math.max(1.0, toDouble(iorExt.get("ior"), ior));
                }
                Map<String, Object> emissiveStrengthExt = asObjectOrNull(extensions.get("KHR_materials_emissive_strength"));
                if (emissiveStrengthExt != null) {
                    emissionStrength = Math.max(0.0, toDouble(emissiveStrengthExt.get("emissiveStrength"), emissionStrength));
                }
                Map<String, Object> clearcoatExt = asObjectOrNull(extensions.get("KHR_materials_clearcoat"));
                if (clearcoatExt != null) {
                    clearcoatFactor = clamp01(toDouble(clearcoatExt.get("clearcoatFactor"), clearcoatFactor));
                    clearcoatRoughness = clamp01(toDouble(clearcoatExt.get("clearcoatRoughnessFactor"), clearcoatRoughness));
                }
                Map<String, Object> specularExt = asObjectOrNull(extensions.get("KHR_materials_specular"));
                if (specularExt != null) {
                    specularFactor = Math.max(0.0, toDouble(specularExt.get("specularFactor"), specularFactor));
                    specularColorFactor = vec3(asList(specularExt.get("specularColorFactor")), specularColorFactor);
                }
                Map<String, Object> sheenExt = asObjectOrNull(extensions.get("KHR_materials_sheen"));
                if (sheenExt != null) {
                    sheenColor = vec3(asList(sheenExt.get("sheenColorFactor")), sheenColor);
                    sheenRoughness = clamp01(toDouble(sheenExt.get("sheenRoughnessFactor"), sheenRoughness));
                }
            }

            if (alphaMode == PhongMaterial.AlphaMode.BLEND) {
                transmission = Math.max(transmission, 1.0 - opacity);
            }

            double shininess = roughnessToShininess(roughness);
            PhongMaterial mat = new PhongMaterial(baseColor, shininess);
            mat.setName(name);
            mat.setDiffuseColor(baseColor);
            mat.setAmbientColor(baseColor.mul(0.08));
            double specularLevel = 0.04 * (1.0 - metallic) + 0.95 * metallic;
            mat.setSpecularColor(new Vec3(
                    clamp01(specularLevel * specularFactor * specularColorFactor.x),
                    clamp01(specularLevel * specularFactor * specularColorFactor.y),
                    clamp01(specularLevel * specularFactor * specularColorFactor.z)
            ));
            mat.setReflectivity(clamp01(0.08 + metallic * 0.48 + (1.0 - roughness) * 0.18 + clearcoatFactor * 0.26));
            mat.setOpacity(opacity);
            mat.setMetallic(metallic);
            mat.setRoughness(roughness);
            mat.setTransmission(transmission);
            mat.setRefractiveIndex(ior);
            mat.setEmissionColor(emissionColor);
            mat.setEmissionStrength(emissionStrength);
            mat.setMediumColor(baseColor.mul(0.7).add(new Vec3(0.15, 0.15, 0.15)));
            mat.setDensity(Math.max(0.0, transmission * 0.08 + (1.0 - opacity) * 0.25));
            mat.setDoubleSided(doubleSided);
            mat.setClearcoatFactor(clearcoatFactor);
            mat.setClearcoatRoughness(clearcoatRoughness);
            mat.setSpecularFactor(specularFactor);
            mat.setSpecularColorFactor(specularColorFactor);
            mat.setSheenColor(sheenColor);
            mat.setSheenRoughness(sheenRoughness);
            mat.setAlphaMode(alphaMode);
            mat.setAlphaCutoff(alphaCutoff);
            if (transmission > 0.1 || alphaMode == PhongMaterial.AlphaMode.BLEND) {
                mat.setShadingModel(engine.material.Material.ShadingModel.TRANSMISSIVE);
            } else if (emissionStrength > 1e-5) {
                mat.setShadingModel(engine.material.Material.ShadingModel.EMISSIVE);
            }

            if (pbr != null) {
                applyTextureInfo(mat.getDiffuseMap(), asObjectOrNull(pbr.get("baseColorTexture")), textures);
                applyTextureInfo(mat.getMetallicRoughnessMap(), asObjectOrNull(pbr.get("metallicRoughnessTexture")), textures);
            }
            applyTextureInfo(mat.getNormalMap(), asObjectOrNull(def.get("normalTexture")), textures);
            applyTextureInfo(mat.getEmissiveMap(), asObjectOrNull(def.get("emissiveTexture")), textures);
            Map<String, Object> normalInfo = asObjectOrNull(def.get("normalTexture"));
            if (normalInfo != null) {
                mat.setNormalScale(Math.max(0.0, toDouble(normalInfo.get("scale"), 1.0)));
            }
            out.add(mat);
        }
        return out;
    }

    private void applyTextureInfo(TextureMap target,
                                  Map<String, Object> texInfo,
                                  List<TextureBinding> textures) {
        if (target == null || texInfo == null) {
            return;
        }
        int textureIndex = intOrDefault(texInfo.get("index"), -1);
        if (textureIndex >= 0 && textureIndex < textures.size()) {
            TextureBinding binding = textures.get(textureIndex);
            if (binding != null) {
                target.setTexture(binding.texture);
                target.setLinear(binding.linear);
            }
        }
        target.setFlipV(false);
        target.setTexCoord(intOrDefault(texInfo.get("texCoord"), 0));
        Map<String, Object> extensions = asObjectOrNull(texInfo.get("extensions"));
        if (extensions == null) {
            return;
        }
        Map<String, Object> transform = asObjectOrNull(extensions.get("KHR_texture_transform"));
        if (transform == null) {
            return;
        }
        List<Object> offset = asList(transform.get("offset"));
        if (offset.size() >= 2) {
            target.setOffsetU(toDouble(offset.get(0), 0.0));
            target.setOffsetV(toDouble(offset.get(1), 0.0));
        }
        List<Object> scale = asList(transform.get("scale"));
        if (scale.size() >= 2) {
            target.setScaleU(toDouble(scale.get(0), 1.0));
            target.setScaleV(toDouble(scale.get(1), 1.0));
        }
        target.setRotation(toDouble(transform.get("rotation"), 0.0));
        if (transform.containsKey("texCoord")) {
            target.setTexCoord(intOrDefault(transform.get("texCoord"), target.getTexCoord()));
        }
    }

    private PhongMaterial.AlphaMode parseAlphaMode(String text) {
        if ("MASK".equalsIgnoreCase(text)) {
            return PhongMaterial.AlphaMode.MASK;
        }
        if ("BLEND".equalsIgnoreCase(text)) {
            return PhongMaterial.AlphaMode.BLEND;
        }
        return PhongMaterial.AlphaMode.OPAQUE;
    }

    private double roughnessToShininess(double roughness) {
        double r = Math.max(0.04, Math.min(1.0, roughness));
        double s = 2.0 / (r * r) - 2.0;
        return Math.max(1.0, Math.min(1024.0, s));
    }

    private PhongMaterial materialForPrimitive(int materialIndex, List<PhongMaterial> materials) {
        if (materials == null || materialIndex < 0 || materialIndex >= materials.size()) {
            return null;
        }
        return cloneMaterial(materials.get(materialIndex));
    }

    private PhongMaterial cloneMaterial(PhongMaterial source) {
        if (source == null) {
            return null;
        }
        return source.copy();
    }

    private Mat4 parseNodeLocalMatrix(Map<String, Object> node) {
        List<Object> matrix = asList(node.get("matrix"));
        if (matrix.size() == 16) {
            return matrixFromColumnMajor(matrix);
        }
        Vec3 t = vec3(asList(node.get("translation")), Vec3.ZERO);
        Vec3 s = vec3(asList(node.get("scale")), Vec3.ONE);
        Quaternion r = quat(asList(node.get("rotation")), new Quaternion());
        return Mat4.translation(t.x, t.y, t.z).multiply(r.toMat4()).multiply(Mat4.scale(s.x, s.y, s.z));
    }

    private int[] resolveRootNodes(List<Map<String, Object>> scenes, int nodeCount, int sceneIndex) {
        if (!scenes.isEmpty() && sceneIndex >= 0 && sceneIndex < scenes.size()) {
            List<Object> nodes = asList(scenes.get(sceneIndex).get("nodes"));
            if (!nodes.isEmpty()) {
                int[] out = new int[nodes.size()];
                for (int i = 0; i < nodes.size(); i++) {
                    out[i] = intOrDefault(nodes.get(i), -1);
                }
                return out;
            }
        }
        int[] out = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            out[i] = i;
        }
        return out;
    }

    private float[] readAccessorFloats(AccessorDef accessor, List<BufferViewDef> views, List<byte[]> buffers) {
        if (accessor.bufferView < 0 || accessor.bufferView >= views.size()) {
            throw new RuntimeException("Accessor bufferView out of range.");
        }
        BufferViewDef view = views.get(accessor.bufferView);
        if (view.buffer < 0 || view.buffer >= buffers.size()) {
            throw new RuntimeException("BufferView buffer out of range.");
        }
        byte[] data = buffers.get(view.buffer);
        int compCount = typeComponentCount(accessor.type);
        int compSize = componentSize(accessor.componentType);
        int stride = view.byteStride > 0 ? view.byteStride : compCount * compSize;
        int start = view.byteOffset + accessor.byteOffset;
        float[] out = new float[accessor.count * compCount];
        for (int i = 0; i < accessor.count; i++) {
            int base = start + i * stride;
            for (int c = 0; c < compCount; c++) {
                int offset = base + c * compSize;
                out[i * compCount + c] = (float) readComponentAsDouble(data, offset, accessor.componentType, accessor.normalized);
            }
        }
        return out;
    }

    private int[] readAccessorIndices(AccessorDef accessor, List<BufferViewDef> views, List<byte[]> buffers) {
        if (accessor.bufferView < 0 || accessor.bufferView >= views.size()) {
            throw new RuntimeException("Index accessor bufferView out of range.");
        }
        BufferViewDef view = views.get(accessor.bufferView);
        if (view.buffer < 0 || view.buffer >= buffers.size()) {
            throw new RuntimeException("Index buffer out of range.");
        }
        byte[] data = buffers.get(view.buffer);
        int compSize = componentSize(accessor.componentType);
        int stride = view.byteStride > 0 ? view.byteStride : compSize;
        int start = view.byteOffset + accessor.byteOffset;
        int[] out = new int[accessor.count];
        for (int i = 0; i < accessor.count; i++) {
            out[i] = readIndexValue(data, start + i * stride, accessor.componentType);
        }
        return out;
    }

    private static int componentSize(int componentType) {
        switch (componentType) {
            case COMPONENT_BYTE:
            case COMPONENT_UBYTE:
                return 1;
            case COMPONENT_SHORT:
            case COMPONENT_USHORT:
                return 2;
            case COMPONENT_UINT:
            case COMPONENT_FLOAT:
                return 4;
            default:
                throw new RuntimeException("Unsupported component type: " + componentType);
        }
    }

    private static int typeComponentCount(String type) {
        if ("SCALAR".equals(type)) return 1;
        if ("VEC2".equals(type)) return 2;
        if ("VEC3".equals(type)) return 3;
        if ("VEC4".equals(type)) return 4;
        if ("MAT2".equals(type)) return 4;
        if ("MAT3".equals(type)) return 9;
        if ("MAT4".equals(type)) return 16;
        throw new RuntimeException("Unsupported accessor type: " + type);
    }

    private static double readComponentAsDouble(byte[] data, int offset, int componentType, boolean normalized) {
        switch (componentType) {
            case COMPONENT_FLOAT:
                return Float.intBitsToFloat(readI32LE(data, offset));
            case COMPONENT_BYTE: {
                int v = data[offset];
                return normalized ? Math.max(-1.0, v / 127.0) : v;
            }
            case COMPONENT_UBYTE: {
                int v = data[offset] & 0xFF;
                return normalized ? (v / 255.0) : v;
            }
            case COMPONENT_SHORT: {
                int v = (short) readU16LE(data, offset);
                return normalized ? Math.max(-1.0, v / 32767.0) : v;
            }
            case COMPONENT_USHORT: {
                int v = readU16LE(data, offset);
                return normalized ? (v / 65535.0) : v;
            }
            case COMPONENT_UINT: {
                long v = readU32LE(data, offset);
                return normalized ? (v / 4294967295.0) : v;
            }
            default:
                throw new RuntimeException("Unsupported component type: " + componentType);
        }
    }

    private static int readIndexValue(byte[] data, int offset, int componentType) {
        switch (componentType) {
            case COMPONENT_UBYTE:
                return data[offset] & 0xFF;
            case COMPONENT_USHORT:
                return readU16LE(data, offset);
            case COMPONENT_UINT: {
                long value = readU32LE(data, offset);
                if (value > Integer.MAX_VALUE) {
                    throw new RuntimeException("Index overflow: " + value);
                }
                return (int) value;
            }
            default:
                throw new RuntimeException("Unsupported index component type: " + componentType);
        }
    }

    private static int readI32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int readU16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long readU32LE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | (((long) (data[offset + 1] & 0xFF)) << 8)
                | (((long) (data[offset + 2] & 0xFF)) << 16)
                | (((long) (data[offset + 3] & 0xFF)) << 24);
    }

    private static Mat4 matrixFromColumnMajor(List<Object> values) {
        double[] m = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                m[row * 4 + col] = doubleOrDefault(values.get(col * 4 + row), row == col ? 1.0 : 0.0);
            }
        }
        return new Mat4(m);
    }

    private static boolean isIdentity(Mat4 matrix) {
        double eps = 1e-9;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double target = row == col ? 1.0 : 0.0;
                if (Math.abs(matrix.get(row, col) - target) > eps) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Vec3 vec3(List<Object> values, Vec3 fallback) {
        if (values.size() < 3) {
            return fallback;
        }
        return new Vec3(
                doubleOrDefault(values.get(0), fallback.x),
                doubleOrDefault(values.get(1), fallback.y),
                doubleOrDefault(values.get(2), fallback.z)
        );
    }

    private static Quaternion quat(List<Object> values, Quaternion fallback) {
        if (values.size() < 4) {
            return fallback;
        }
        return new Quaternion(
                doubleOrDefault(values.get(0), fallback.x),
                doubleOrDefault(values.get(1), fallback.y),
                doubleOrDefault(values.get(2), fallback.z),
                doubleOrDefault(values.get(3), fallback.w)
        ).normalize();
    }

    private static GlbData parseGlb(byte[] bytes, String filePath) {
        if (bytes.length < 20) {
            throw new RuntimeException("GLB too small: " + filePath);
        }
        int magic = readI32LE(bytes, 0);
        int version = readI32LE(bytes, 4);
        long length = readU32LE(bytes, 8);
        if (magic != GLB_MAGIC) {
            throw new RuntimeException("Invalid GLB magic: " + filePath);
        }
        if (version != 2) {
            throw new RuntimeException("Unsupported GLB version: " + version);
        }
        if (length > bytes.length) {
            throw new RuntimeException("Corrupt GLB length.");
        }

        int offset = 12;
        String json = null;
        byte[] bin = null;
        while (offset + 8 <= bytes.length) {
            int chunkLength = readI32LE(bytes, offset);
            int chunkType = readI32LE(bytes, offset + 4);
            offset += 8;
            if (chunkLength < 0 || offset + chunkLength > bytes.length) {
                throw new RuntimeException("Invalid GLB chunk length.");
            }
            if (chunkType == GLB_CHUNK_JSON) {
                json = new String(bytes, offset, chunkLength, StandardCharsets.UTF_8).trim();
            } else if (chunkType == GLB_CHUNK_BIN) {
                bin = new byte[chunkLength];
                System.arraycopy(bytes, offset, bin, 0, chunkLength);
            }
            offset += chunkLength;
        }
        if (json == null) {
            throw new RuntimeException("GLB missing JSON chunk.");
        }
        return new GlbData(json, bin);
    }

    private static byte[] decodeDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            throw new RuntimeException("Invalid data URI.");
        }
        String meta = uri.substring(0, comma);
        String payload = uri.substring(comma + 1);
        if (meta.contains(";base64")) {
            return Base64.getDecoder().decode(payload);
        }
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String context) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new RuntimeException("Expected object for " + context);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map)) {
            return null;
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List)) {
            throw new RuntimeException("Expected array value.");
        }
        return (List<Object>) value;
    }

    private static List<Map<String, Object>> asObjectList(Object value) {
        List<Object> arr = asList(value);
        List<Map<String, Object>> out = new ArrayList<>(arr.size());
        for (Object item : arr) {
            out.add(asObject(item, "array item"));
        }
        return out;
    }

    private static int intOrDefault(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double doubleOrDefault(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean booleanOrDefault(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static String stringOrDefault(Object value, String fallback) {
        String text = stringOrNull(value);
        return text == null ? fallback : text;
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private static double toDouble(Object value, double fallback) {
        return doubleOrDefault(value, fallback);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class GlbData {
        final String json;
        final byte[] binChunk;

        GlbData(String json, byte[] binChunk) {
            this.json = json;
            this.binChunk = binChunk;
        }
    }
}
