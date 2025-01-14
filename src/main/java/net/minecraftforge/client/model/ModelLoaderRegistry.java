/*
 * Minecraft Forge
 * Copyright (c) 2016-2019.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.client.model;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.TransformationMatrix;
import net.minecraft.client.renderer.model.*;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.MissingTextureSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.Direction;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.composite.CompositeModel;
import net.minecraftforge.client.model.geometry.IModelGeometry;
import net.minecraftforge.client.model.geometry.ISimpleModelGeometry;
import net.minecraftforge.client.model.obj.OBJLoader;
import net.minecraftforge.common.model.TransformationHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central hub for custom model loaders.
 */
public class ModelLoaderRegistry
{
    private static final Map<ResourceLocation, IModelLoader<?>> loaders = Maps.newHashMap();

    // Forge built-in loaders
    public static void init()
    {
        registerLoader(new ResourceLocation("forge:obj"), OBJLoader.INSTANCE);
        registerLoader(new ResourceLocation("forge:bucket"), ModelDynBucket.LoaderDynBucket2.INSTANCE);
        registerLoader(new ResourceLocation("forge:composite"), CompositeModel.Loader.INSTANCE);
        registerLoader(new ResourceLocation("minecraft:elements"), VanillaProxy.Loader.INSTANCE);

        // TODO: Implement as new model loaders
        //registerLoader(new ResourceLocation("forge:b3d"), new ModelLoaderAdapter(B3DLoader.INSTANCE));
        //registerLoader(new ResourceLocation("forge:fluid"), new ModelLoaderAdapter(ModelFluid.FluidLoader.INSTANCE));
        //registerLoader(new ResourceLocation("forge:multi-layer"), new ModelLoaderAdapter(MultiLayerModel.Loader.INSTANCE));
    }

    /**
     * Makes system aware of your loader.
     */
    public static void registerLoader(ResourceLocation id, IModelLoader<?> loader)
    {
        loaders.put(id, loader);
        ((IReloadableResourceManager) Minecraft.getInstance().getResourceManager()).addReloadListener(loader);
    }

    public static IModelGeometry<?> getModel(ResourceLocation loaderId, JsonDeserializationContext deserializationContext, JsonObject data)
    {
        try
        {
            if (!loaders.containsKey(loaderId))
                throw new IllegalStateException(String.format("Model loader '%s' not found.", loaderId));

            IModelLoader<?> loader = loaders.get(loaderId);

            return loader.read(deserializationContext, data);
        }
        catch(Exception e)
        {
            e.printStackTrace();
            throw e;
        }
    }

    @Nullable
    public static IModelGeometry<?> deserializeGeometry(JsonDeserializationContext deserializationContext, JsonObject object) {
        if (!object.has("loader")) {
            return null;
        }

        ResourceLocation loader = new ResourceLocation(JSONUtils.getString(object,"loader"));
        return getModel(loader, deserializationContext, object);
    }

    private static final Pattern FILESYSTEM_PATH_TO_RESLOC = Pattern.compile("(?:.*[\\\\/]assets[\\\\/](?<namespace>[a-z_-]+)[\\\\/]textures[\\\\/])?(?<path>[a-z_\\\\/-]+)\\.png");

    public static Material resolveTexture(@Nullable String tex, IModelConfiguration owner)
    {
        if (tex == null)
            return blockMaterial("forge:white");
        if (tex.startsWith("#"))
            return owner.resolveTexture(tex);

        // Attempt to convert a common (windows/linux/mac) filesystem path to a ResourceLocation.
        // This makes no promises, if it doesn't work, too bad, fix your mtl file.
        Matcher match = FILESYSTEM_PATH_TO_RESLOC.matcher(tex);
        if (match.matches())
        {
            String namespace = match.group("namespace");
            String path = match.group("path").replace("\\", "/");
            if (namespace != null)
                return blockMaterial(new ResourceLocation(namespace, path));
            return blockMaterial(path);
        }

        return blockMaterial(tex);
    }

    public static Material blockMaterial(String location)
    {
        return new Material(AtlasTexture.LOCATION_BLOCKS_TEXTURE, new ResourceLocation(location));
    }

    public static Material blockMaterial(ResourceLocation location)
    {
        return new Material(AtlasTexture.LOCATION_BLOCKS_TEXTURE, location);
    }

    @Nullable
    public static IModelTransform deserializeModelTransforms(JsonDeserializationContext deserializationContext, JsonObject modelData)
    {
        if (!modelData.has("transform"))
            return null;

        return deserializeTransform(deserializationContext, modelData.get("transform")).orElse(null);
    }

    public static Optional<IModelTransform> deserializeTransform(JsonDeserializationContext context, JsonElement transformData)
    {
        if (!transformData.isJsonObject())
        {
            try
            {
                TransformationMatrix base = context.deserialize(transformData, TransformationMatrix.class);
                return Optional.of(new SimpleModelTransform(ImmutableMap.of(), TransformationHelper.blockCenterToCorner(base)));
            }
            catch (JsonParseException e)
            {
                throw new JsonParseException("transform: expected a string, object or valid base transformation, got: " + transformData);
            }
        }
        else
        {
            JsonObject transform = transformData.getAsJsonObject();
            EnumMap<ItemCameraTransforms.TransformType, TransformationMatrix> transforms = Maps.newEnumMap(ItemCameraTransforms.TransformType.class);

            deserializeTRSR(context, transforms, transform, "thirdperson", ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND);
            deserializeTRSR(context, transforms, transform, "thirdperson_righthand", ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND);
            deserializeTRSR(context, transforms, transform, "thirdperson_lefthand", ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND);

            deserializeTRSR(context, transforms, transform, "firstperson", ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND);
            deserializeTRSR(context, transforms, transform, "firstperson_righthand", ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND);
            deserializeTRSR(context, transforms, transform, "firstperson_lefthand", ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND);

            deserializeTRSR(context, transforms, transform, "head", ItemCameraTransforms.TransformType.HEAD);
            deserializeTRSR(context, transforms, transform, "gui", ItemCameraTransforms.TransformType.GUI);
            deserializeTRSR(context, transforms, transform, "ground", ItemCameraTransforms.TransformType.GROUND);
            deserializeTRSR(context, transforms, transform, "fixed", ItemCameraTransforms.TransformType.FIXED);

            int k = transform.entrySet().size();
            if(transform.has("matrix")) k--;
            if(transform.has("translation")) k--;
            if(transform.has("rotation")) k--;
            if(transform.has("scale")) k--;
            if(transform.has("post-rotation")) k--;
            if(k > 0)
            {
                throw new JsonParseException("transform: allowed keys: 'thirdperson', 'firstperson', 'gui', 'head', 'matrix', 'translation', 'rotation', 'scale', 'post-rotation'");
            }
            TransformationMatrix base = TransformationMatrix.func_227983_a_();
            if(!transform.entrySet().isEmpty())
            {
                base = context.deserialize(transform, TransformationMatrix.class);
                base = TransformationHelper.blockCenterToCorner(base);
            }
            IModelTransform state = new SimpleModelTransform(Maps.immutableEnumMap(transforms), base);
            return Optional.of(state);
        }
    }

    private static void deserializeTRSR(JsonDeserializationContext context, EnumMap<ItemCameraTransforms.TransformType, TransformationMatrix> transforms, JsonObject transform, String name, ItemCameraTransforms.TransformType itemCameraTransform)
    {
        if(transform.has(name))
        {
            TransformationMatrix t = context.deserialize(transform.remove(name), TransformationMatrix.class);
            transforms.put(itemCameraTransform, TransformationHelper.blockCenterToCorner(t));
        }
    }

    public static IBakedModel bakeHelper(BlockModel blockModel, ModelBakery modelBakery, BlockModel otherModel, Function<Material, TextureAtlasSprite> spriteGetter, IModelTransform sprite, ResourceLocation modelLocation)
    {
        IBakedModel model;
        IModelGeometry<?> customModel = blockModel.customData.getCustomGeometry();
        IModelTransform customModelState = blockModel.customData.getCustomModelState();
        if (customModelState != null)
        {
            sprite = new ModelTransformComposition(customModelState, sprite, sprite.isUvLock());
        }
        if (customModel != null)
        {
            model = customModel.bake(blockModel.customData, modelBakery, spriteGetter, sprite, blockModel.getOverrides(modelBakery, otherModel, spriteGetter), modelLocation);
        }
        else
        {
            model = blockModel.bakeVanilla(modelBakery, otherModel, spriteGetter, sprite, modelLocation);
        }

        if (customModelState != null && !model.doesHandlePerspectives())
        {
            model = new PerspectiveMapWrapper(model, customModelState);
        }

        return model;
    }

    public static class VanillaProxy implements ISimpleModelGeometry<VanillaProxy>
    {
        private final List<BlockPart> elements;

        public VanillaProxy(List<BlockPart> list)
        {
            this.elements = list;
        }

        @Override
        public void addQuads(IModelConfiguration owner, IModelBuilder<?> modelBuilder, ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, IModelTransform sprite, ResourceLocation modelLocation)
        {
            for(BlockPart blockpart : elements) {
                for(Direction direction : blockpart.mapFaces.keySet()) {
                    BlockPartFace blockpartface = blockpart.mapFaces.get(direction);
                    TextureAtlasSprite textureatlassprite1 = spriteGetter.apply(owner.resolveTexture(blockpartface.texture));
                    if (blockpartface.cullFace == null) {
                        modelBuilder.addGeneralQuad(BlockModel.makeBakedQuad(blockpart, blockpartface, textureatlassprite1, direction, sprite, modelLocation));
                    } else {
                        modelBuilder.addFaceQuad(
                                sprite.func_225615_b_().rotateTransform(blockpartface.cullFace),
                                BlockModel.makeBakedQuad(blockpart, blockpartface, textureatlassprite1, direction, sprite, modelLocation));
                    }
                }
            }
        }

        @Override
        public Collection<Material> getTextures(IModelConfiguration owner, Function<ResourceLocation, IUnbakedModel> modelGetter, Set<Pair<String, String>> missingTextureErrors)
        {
            Set<Material> textures = Sets.newHashSet();

            for(BlockPart part : elements) {
                for(BlockPartFace face : part.mapFaces.values()) {
                    Material texture = owner.resolveTexture(face.texture);
                    if (Objects.equals(texture, MissingTextureSprite.getLocation().toString())) {
                        missingTextureErrors.add(Pair.of(face.texture, owner.getModelName()));
                    }

                    textures.add(texture);
                }
            }

            return textures;
        }

        public static class Loader implements IModelLoader<VanillaProxy>
        {
            public static final Loader INSTANCE = new Loader();

            private Loader()
            {
            }

            @Override
            public void onResourceManagerReload(IResourceManager resourceManager)
            {

            }

            @Override
            public VanillaProxy read(JsonDeserializationContext deserializationContext, JsonObject modelContents)
            {
                List<BlockPart> list = this.getModelElements(deserializationContext, modelContents);
                return new VanillaProxy(list);
            }

            private List<BlockPart> getModelElements(JsonDeserializationContext deserializationContext, JsonObject object) {
                List<BlockPart> list = Lists.newArrayList();
                if (object.has("elements")) {
                    for(JsonElement jsonelement : JSONUtils.getJsonArray(object, "elements")) {
                        list.add(deserializationContext.deserialize(jsonelement, BlockPart.class));
                    }
                }

                return list;
            }
        }
    }

    public static class ExpandedBlockModelDeserializer extends BlockModel.Deserializer
    {
        public static final Gson INSTANCE = (new GsonBuilder())
                .registerTypeAdapter(BlockModel.class, new ExpandedBlockModelDeserializer())
                .registerTypeAdapter(BlockPart.class, new BlockPart.Deserializer())
                .registerTypeAdapter(BlockPartFace.class, new BlockPartFace.Deserializer())
                .registerTypeAdapter(BlockFaceUV.class, new BlockFaceUV.Deserializer())
                .registerTypeAdapter(ItemTransformVec3f.class, new ItemTransformVec3f.Deserializer())
                .registerTypeAdapter(ItemCameraTransforms.class, new ItemCameraTransforms.Deserializer())
                .registerTypeAdapter(ItemOverride.class, new ItemOverride.Deserializer())
                .registerTypeAdapter(TransformationMatrix.class, new TransformationHelper.Deserializer())
                .create();

        public BlockModel deserialize(JsonElement element, Type targetType, JsonDeserializationContext deserializationContext) throws JsonParseException {
            BlockModel model = super.deserialize(element, targetType, deserializationContext);
            JsonObject jsonobject = element.getAsJsonObject();
            IModelGeometry<?> geometry = deserializeGeometry(deserializationContext, jsonobject);

            List<BlockPart> elements = model.getElements();
            if (geometry != null) {
                elements.clear();
                model.customData.setCustomGeometry(geometry);
            }

            IModelTransform modelState = deserializeModelTransforms(deserializationContext, jsonobject);
            if (modelState != null)
            {
                model.customData.setCustomModelState(modelState);
            }

            if (jsonobject.has("visibility"))
            {
                JsonObject visibility = JSONUtils.getJsonObject(jsonobject, "visibility");
                for(Map.Entry<String, JsonElement> part : visibility.entrySet())
                {
                    model.customData.visibilityData.setVisibilityState(part.getKey(), part.getValue().getAsBoolean());
                }
            }

            return model;
        }
    }
}
