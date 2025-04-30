package com.bumptech.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pools.Pool;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.ImageHeaderParser;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.data.DataRewinderRegistry;
import com.bumptech.glide.load.engine.DecodePath;
import com.bumptech.glide.load.engine.LoadPath;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.ModelLoaderRegistry;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.load.resource.transcode.TranscoderRegistry;
import com.bumptech.glide.provider.EncoderRegistry;
import com.bumptech.glide.provider.ImageHeaderParserRegistry;
import com.bumptech.glide.provider.LoadPathCache;
import com.bumptech.glide.provider.ModelToResourceClassCache;
import com.bumptech.glide.provider.ResourceDecoderRegistry;
import com.bumptech.glide.provider.ResourceEncoderRegistry;
import com.bumptech.glide.util.pool.FactoryPools;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages component registration to extend or replace Glide's default loading, decoding, and
 * encoding logic.
 */
// Public API.
@SuppressWarnings({"WeakerAccess", "unused"})
public class Registry {
  public static final String BUCKET_ANIMATION = "Animation";
  /**
   * @deprecated Identical to {@link #BUCKET_ANIMATION}, just with a more confusing name. This
   * bucket can be used for all animation types (including webp).
   */
  @Deprecated public static final String BUCKET_GIF = BUCKET_ANIMATION;

  public static final String BUCKET_BITMAP = "Bitmap";
  public static final String BUCKET_BITMAP_DRAWABLE = "BitmapDrawable";
  private static final String BUCKET_PREPEND_ALL = "legacy_prepend_all";
  private static final String BUCKET_APPEND_ALL = "legacy_append";
  /**
   * 模型加载表 (Model, Data, ModelLoaderFactory)(模型，数据，模型工厂)
   * e.g. (GlideUrl.class, InputStream.class, new HttpGlideUrlLoader.Factory())
   */
  private final ModelLoaderRegistry modelLoaderRegistry;
  /**
   * 编码器表 (Data, Encoder) (数据，编码器)
   * e.g. (ByteBuffer.class, new ByteBufferEncoder())
   */
  private final EncoderRegistry encoderRegistry;
  /**
   * 资源解码器表 (bucket, Data, ResourceClass, Decoder) (存储桶标识符，数据，资源类，解码器)
   * e.g. (Registry.BUCKET_BITMAP, ParcelFileDescriptor.class, Bitmap.class, new ParcelFileDescriptorBitmapDecoder())
   */
  private final ResourceDecoderRegistry decoderRegistry;
  /**
   * 资源编码器表 (Resource, ResourceEncoder)（资源，资源编码器）
   * e.g. (Bitmap.class, new BitmapEncoder())
   */
  private final ResourceEncoderRegistry resourceEncoderRegistry;
  /**
   * 数据重写工厂表 (Data, DataRewinder.Factory)（数据，数据重写工厂）
   * 用于支持重复读取数据（如 InputStream）
   * e.g. (InputStream.class, new InputStreamRewinder.Factory())
   */
  private final DataRewinderRegistry dataRewinderRegistry;
  /**
   * 转码器表 (Resource, Transcode, Transcoder)（资源，转码，转码器）
   * e.g. (Bitmap.class, BitmapDrawable.class, new BitmapDrawableTranscoder())
   */
  private final TranscoderRegistry transcoderRegistry;
  /**
   * 图片头信息解析器表（ImageHeaderParser）
   * 用于解析图片 EXIF、方向等信息
   * e.g. new DefaultImageHeaderParser()
   */
  private final ImageHeaderParserRegistry imageHeaderParserRegistry;
  /**
   * 模型到资源类的缓存表 (Model, Resource, Transcode -> ResourceClass List)（模型，资源，转码器列表）
   * 加速获取解码链路，避免重复计算
   */
  private final ModelToResourceClassCache modelToResourceClassCache = new ModelToResourceClassCache();

  /**
   * 保存从数据源（dataClass）到目标转码类型（transcodeClass）的解码路径
   */
  private final LoadPathCache loadPathCache = new LoadPathCache();
  private final Pool<List<Throwable>> throwableListPool = FactoryPools.threadSafeList();

  public Registry() {
    this.modelLoaderRegistry = new ModelLoaderRegistry(throwableListPool);
    this.encoderRegistry = new EncoderRegistry();
    this.decoderRegistry = new ResourceDecoderRegistry();
    this.resourceEncoderRegistry = new ResourceEncoderRegistry();
    this.dataRewinderRegistry = new DataRewinderRegistry();
    this.transcoderRegistry = new TranscoderRegistry();
    this.imageHeaderParserRegistry = new ImageHeaderParserRegistry();
    setResourceDecoderBucketPriorityList(Arrays.asList(BUCKET_ANIMATION, BUCKET_BITMAP, BUCKET_BITMAP_DRAWABLE));
  }

  /**
   * Registers the given {@link Encoder} for the given data class (InputStream, FileDescriptor etc).
   *
   * <p>The {@link Encoder} will be used both for the exact data class and any subtypes. For
   * example, registering an {@link Encoder} for {@link java.io.InputStream} will result in the
   * {@link Encoder} being used for {@link
   * android.content.res.AssetFileDescriptor.AutoCloseInputStream}, {@link java.io.FileInputStream}
   * and any other subclass.
   *
   * <p>If multiple {@link Encoder}s are registered for the same type or super type, the {@link
   * Encoder} that is registered first will be used.
   *
   * @deprecated Use the equivalent {@link #append(Class, Class, ModelLoaderFactory)} method
   * instead.
   */
  @NonNull
  @Deprecated
  public <Data> Registry register(@NonNull Class<Data> dataClass, @NonNull Encoder<Data> encoder) {
    return append(dataClass, encoder);
  }

  /**
   * Appends the given {@link Encoder} onto the list of available {@link Encoder}s so that it is
   * attempted after all earlier and default {@link Encoder}s for the given data class.
   *
   * <p>The {@link Encoder} will be used both for the exact data class and any subtypes. For
   * example, registering an {@link Encoder} for {@link java.io.InputStream} will result in the
   * {@link Encoder} being used for {@link
   * android.content.res.AssetFileDescriptor.AutoCloseInputStream}, {@link java.io.FileInputStream}
   * and any other subclass.
   *
   * <p>If multiple {@link Encoder}s are registered for the same type or super type, the {@link
   * Encoder} that is registered first will be used.
   *
   * @see #prepend(Class, Encoder)
   */
  @NonNull
  public <Data> Registry append(@NonNull Class<Data> dataClass, @NonNull Encoder<Data> encoder) {
    encoderRegistry.append(dataClass, encoder);
    return this;
  }

  /**
   * Prepends the given {@link Encoder} into the list of available {@link Encoder}s so that it is
   * attempted before all later and default {@link Encoder}s for the given data class.
   *
   * <p>This method allows you to replace the default {@link Encoder} because it ensures the
   * registered {@link Encoder} will run first. If multiple {@link Encoder}s are registered for the
   * same type or super type, the {@link Encoder} that is registered first will be used.
   *
   * @see #append(Class, Encoder)
   */
  @NonNull
  public <Data> Registry prepend(@NonNull Class<Data> dataClass, @NonNull Encoder<Data> encoder) {
    encoderRegistry.prepend(dataClass, encoder);
    return this;
  }

  /**
   * Appends the given {@link ResourceDecoder} onto the list of all available {@link
   * ResourceDecoder}s allowing it to be used if all earlier and default {@link ResourceDecoder}s
   * for the given types fail (or there are none).
   *
   * <p>If you're attempting to replace an existing {@link ResourceDecoder} or would like to ensure
   * that your {@link ResourceDecoder} gets the chance to run before an existing {@link
   * ResourceDecoder}, use {@link #prepend(Class, Class, ResourceDecoder)}. This method is best for
   * new types of resources and data or as a way to add an additional fallback decoder for an
   * existing type of data.
   *
   * @param dataClass     The data that will be decoded from ({@link java.io.InputStream}, {@link
   *                      java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #append(String, Class, Class, ResourceDecoder)
   * @see #prepend(Class, Class, ResourceDecoder)
   */
  @NonNull
  public <Data, TResource> Registry append(
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    append(BUCKET_APPEND_ALL, dataClass, resourceClass, decoder);
    return this;
  }

  /**
   * Appends the given {@link ResourceDecoder} onto the list of available {@link ResourceDecoder}s
   * in this bucket, allowing it to be used if all earlier and default {@link ResourceDecoder}s for
   * the given types in this bucket fail (or there are none).
   *
   * <p>If you're attempting to replace an existing {@link ResourceDecoder} or would like to ensure
   * that your {@link ResourceDecoder} gets the chance to run before an existing {@link
   * ResourceDecoder}, use {@link #prepend(Class, Class, ResourceDecoder)}. This method is best for
   * new types of resources and data or as a way to add an additional fallback decoder for an
   * existing type of data.
   *
   * @param bucket        The bucket identifier to add this decoder to.
   * @param dataClass     The data that will be decoded from ({@link java.io.InputStream}, {@link
   *                      java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #prepend(String, Class, Class, ResourceDecoder)
   * @see #setResourceDecoderBucketPriorityList(List)
   */
  @NonNull
  public <Data, TResource> Registry append(
      @NonNull String bucket,
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    decoderRegistry.append(bucket, decoder, dataClass, resourceClass);
    return this;
  }

  /**
   * Prepends the given {@link ResourceDecoder} into the list of all available {@link
   * ResourceDecoder}s so that it is attempted before all later and default {@link ResourceDecoder}s
   * for the given types.
   *
   * <p>This method allows you to replace the default {@link ResourceDecoder} because it ensures the
   * registered {@link ResourceDecoder} will run first. You can use the {@link
   * ResourceDecoder#handles(Object, Options)} to fall back to the default {@link ResourceDecoder}s
   * if you only want to change the default functionality for certain types of data.
   *
   * @param dataClass     The data that will be decoded from ({@link java.io.InputStream}, {@link
   *                      java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #prepend(String, Class, Class, ResourceDecoder)
   * @see #append(Class, Class, ResourceDecoder)
   */
  @NonNull
  public <Data, TResource> Registry prepend(
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    prepend(BUCKET_PREPEND_ALL, dataClass, resourceClass, decoder);
    return this;
  }

  /**
   * Prepends the given {@link ResourceDecoder} into the list of available {@link ResourceDecoder}s
   * in the same bucket so that it is attempted before all later and default {@link
   * ResourceDecoder}s for the given types in that bucket.
   *
   * <p>This method allows you to replace the default {@link ResourceDecoder} for this bucket
   * because it ensures the registered {@link ResourceDecoder} will run first. You can use the
   * {@link ResourceDecoder#handles(Object, Options)} to fall back to the default {@link
   * ResourceDecoder}s if you only want to change the default functionality for certain types of
   * data.
   *
   * @param bucket        The bucket identifier to add this decoder to.
   * @param dataClass     The data that will be decoded from ({@link java.io.InputStream}, {@link
   *                      java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #append(String, Class, Class, ResourceDecoder)
   * @see #setResourceDecoderBucketPriorityList(List)
   */
  @NonNull
  public <Data, TResource> Registry prepend(
      @NonNull String bucket,
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    decoderRegistry.prepend(bucket, decoder, dataClass, resourceClass);
    return this;
  }

  /**
   * Overrides the default ordering of resource decoder buckets. You may also add custom buckets
   * which are identified as a unique string. Glide will attempt to decode using decoders in the
   * highest priority bucket before moving on to the next one.
   *
   * <p>The default order is [{@link #BUCKET_ANIMATION}, {@link #BUCKET_BITMAP}, {@link
   * #BUCKET_BITMAP_DRAWABLE}].
   *
   * <p>When registering decoders, you can use these buckets to specify the ordering relative only
   * to other decoders in that bucket.
   *
   * @param buckets The list of bucket identifiers in order from highest priority to least priority.
   * @see #append(String, Class, Class, ResourceDecoder)
   * @see #prepend(String, Class, Class, ResourceDecoder)
   */
  // Final to avoid a PMD error.
  @NonNull
  public final Registry setResourceDecoderBucketPriorityList(@NonNull List<String> buckets) {
    // See #3296 and https://bugs.openjdk.java.net/browse/JDK-6260652.
    List<String> modifiedBuckets = new ArrayList<>(buckets.size());
    modifiedBuckets.add(BUCKET_PREPEND_ALL);
    // See https://github.com/bumptech/glide/issues/4309.
    for (String bucket : buckets) {
      modifiedBuckets.add(bucket);
    }
    modifiedBuckets.add(BUCKET_APPEND_ALL);
    decoderRegistry.setBucketPriorityList(modifiedBuckets);
    return this;
  }

  /**
   * Appends the given {@link ResourceEncoder} into the list of available {@link ResourceEncoder}s
   * so that it is attempted after all earlier and default {@link ResourceEncoder}s for the given
   * data type.
   *
   * <p>The {@link ResourceEncoder} will be used both for the exact resource class and any subtypes.
   * For example, registering an {@link ResourceEncoder} for {@link
   * android.graphics.drawable.Drawable} (not recommended) will result in the {@link
   * ResourceEncoder} being used for {@link android.graphics.drawable.BitmapDrawable} and {@link
   * com.bumptech.glide.load.resource.gif.GifDrawable} and any other subclass.
   *
   * <p>If multiple {@link ResourceEncoder}s are registered for the same type or super type, the
   * {@link ResourceEncoder} that is registered first will be used.
   *
   * @deprecated Use the equivalent {@link #append(Class, ResourceEncoder)} method instead.
   */
  @NonNull
  @Deprecated
  public <TResource> Registry register(
      @NonNull Class<TResource> resourceClass, @NonNull ResourceEncoder<TResource> encoder) {
    return append(resourceClass, encoder);
  }

  /**
   * Appends the given {@link ResourceEncoder} into the list of available {@link ResourceEncoder}s
   * so that it is attempted after all earlier and default {@link ResourceEncoder}s for the given
   * data type.
   *
   * <p>The {@link ResourceEncoder} will be used both for the exact resource class and any subtypes.
   * For example, registering an {@link ResourceEncoder} for {@link
   * android.graphics.drawable.Drawable} (not recommended) will result in the {@link
   * ResourceEncoder} being used for {@link android.graphics.drawable.BitmapDrawable} and {@link
   * com.bumptech.glide.load.resource.gif.GifDrawable} and any other subclass.
   *
   * <p>If multiple {@link ResourceEncoder}s are registered for the same type or super type, the
   * {@link ResourceEncoder} that is registered first will be used.
   *
   * @see #prepend(Class, ResourceEncoder)
   */
  @NonNull
  public <TResource> Registry append(
      @NonNull Class<TResource> resourceClass, @NonNull ResourceEncoder<TResource> encoder) {
    resourceEncoderRegistry.append(resourceClass, encoder);
    return this;
  }

  /**
   * Prepends the given {@link ResourceEncoder} into the list of available {@link ResourceEncoder}s
   * so that it is attempted before all later and default {@link ResourceEncoder}s for the given
   * data type.
   *
   * <p>This method allows you to replace the default {@link ResourceEncoder} because it ensures the
   * registered {@link ResourceEncoder} will run first. If multiple {@link ResourceEncoder}s are
   * registered for the same type or super type, the {@link ResourceEncoder} that is registered
   * first will be used.
   *
   * @see #append(Class, ResourceEncoder)
   */
  @NonNull
  public <TResource> Registry prepend(
      @NonNull Class<TResource> resourceClass, @NonNull ResourceEncoder<TResource> encoder) {
    resourceEncoderRegistry.prepend(resourceClass, encoder);
    return this;
  }

  /**
   * Registers a new {@link com.bumptech.glide.load.data.DataRewinder.Factory} to handle a
   * non-default data type that can be rewind to allow for efficient reads of file headers.
   */
  @NonNull
  public Registry register(@NonNull DataRewinder.Factory<?> factory) {
    dataRewinderRegistry.register(factory);
    return this;
  }

  /**
   * Registers the given {@link ResourceTranscoder} to convert from the given resource {@link Class}
   * to the given transcode {@link Class}.
   *
   * @param resourceClass  The class that will be transcoded from (e.g. {@link
   *                       android.graphics.Bitmap}).
   * @param transcodeClass The class that will be transcoded to (e.g. {@link
   *                       android.graphics.drawable.BitmapDrawable}).
   * @param transcoder     The {@link ResourceTranscoder} to register.
   */
  @NonNull
  public <TResource, Transcode> Registry register(
      @NonNull Class<TResource> resourceClass,
      @NonNull Class<Transcode> transcodeClass,
      @NonNull ResourceTranscoder<TResource, Transcode> transcoder) {
    transcoderRegistry.register(resourceClass, transcodeClass, transcoder);
    return this;
  }

  /**
   * Registers a new {@link ImageHeaderParser} that can obtain some basic metadata from an image
   * header (orientation, type etc).
   */
  @NonNull
  public Registry register(@NonNull ImageHeaderParser parser) {
    imageHeaderParserRegistry.add(parser);
    return this;
  }

  /**
   * 将新的 {@link ModelLoaderFactory} 附加到现有集合的末尾，以便在所有默认和先前为给定模型和数据类注册的 {@link ModelLoader} 之后尝试构造的 {@link ModelLoader}。
   * 如果您尝试替换现有的 {@link ModelLoader}，请使用 {@link #prepend(Class, Class, ModelLoaderFactory)}。
   * 此方法最适合新类型的模型或数据，或作为为现有类型的模型/数据添加额外后备加载器的方式。
   * 如果为同一个模型和/或数据类注册了多个 {@link ModelLoaderFactory}，则将按照 {@link ModelLoaderFactory} 的注册顺序尝试它们生成的 {@link ModelLoader}。
   * 只有当所有 {@link ModelLoader} 都失败时，整个请求才会失败。
   *
   * <p>要处理新类型的数据或为 Glide 的默认行为添加回退，请使用 append() 。 append() 将确保仅在尝试 Glide 的默认行为后才调用 ModelLoader 或 ResourceDecoder 。
   * 如果您尝试处理 Glide 默认组件处理的子类型（例如特定的 Uri 权限或子类），则可能需要使用 prepend() 来确保 Glide 的默认组件不会在您的自定义组件之前加载资源。
   *
   * @param modelClass 模型类 (e.g. URL, {@link java.io.File}).
   * @param dataClass  数据类 (e.g. {@link java.io.InputStream}, {@link java.io.FileDescriptor}).
   * @see #prepend(Class, Class, ModelLoaderFactory)
   * @see #replace(Class, Class, ModelLoaderFactory)
   */
  @NonNull
  public <Model, Data> Registry append(
      @NonNull Class<Model> modelClass,
      @NonNull Class<Data> dataClass,
      @NonNull ModelLoaderFactory<Model, Data> factory) {
    modelLoaderRegistry.append(modelClass, dataClass, factory);
    return this;
  }

  /**
   * 将新的 {@link ModelLoaderFactory} 添加到现有集合的开头，以便
   * 构造的 {@link ModelLoader} 将在所有默认和先前注册的
   * 给定模型和数据类的 {@link ModelLoader} 之前尝试。
   *
   * <p>如果您尝试添加其他功能或添加仅在默认 {@link ModelLoader} 运行后运行的备份，请使用 {@link #append(Class, Class, ModelLoaderFactory)}。
   * 此方法最适合在 Glide 现有的功能中添加应首先运行的附加用例。如果前置的 {@link ModelLoader} 失败，此方法仍将运行 Glide 的默认 {@link ModelLoader}。
   *
   * <p>如果为同一个模型和/或数据类注册了多个 {@link ModelLoaderFactory}，则将按照 {@link ModelLoaderFactory} 注册的顺序尝试它们生成的 {@link ModelLoader}。
   * 只有当所有 {@link ModelLoader} 都失败时，整个请求才会失败。
   *
   * <p>如果您希望在 ModelLoader 或 ResourceDecoder 失败时回退到 Glide 的默认行为，可以使用 prepend() 来处理现有数据的子集。
   * prepend prepend() 将确保您的 ModelLoader 或 ResourceDecoder 在所有其他先前注册的组件之前被调用，并可以优先运行。
   * 如果您的 ModelLoader 或 ResourceDecoder 从其 handles() 方法返回 false 或失败，则所有其他 ModelLoader 或 ResourceDecoders 将按照它们注册的顺序逐一调用，从而提供回退功能。
   *
   * @param modelClass 模型类（例如 URL、文件路径）
   * @param dataClass 数据类（例如 {@link java.io.InputStream}、{@link java.io.FileDescriptor}）
   * @see #append(Class, Class, ModelLoaderFactory)
   * @see #replace(Class, Class, ModelLoaderFactory)
   */
  @NonNull
  public <Model, Data> Registry prepend(
      @NonNull Class<Model> modelClass,
      @NonNull Class<Data> dataClass,
      @NonNull ModelLoaderFactory<Model, Data> factory) {
    modelLoaderRegistry.prepend(modelClass, dataClass, factory);
    return this;
  }

  /**
   * 移除所有默认的和之前为给定数据和模型类注册的 {@link ModelLoaderFactory}，并将它们全部替换为提供的单个 {@link ModelLoader}。
   *
   * <p>如果您尝试添加其他功能或添加仅在默认 {@link ModelLoader} 运行后运行的备份，
   * 请使用 {@link #append(Class, Class, ModelLoaderFactory)}。仅当您希望确保 Glide 的默认 {@link ModelLoader} 不运行时，才应使用此方法。
   *
   * <p>此方法的一个良好用例是，当您想将 Glide 的默认网络库替换为 OkHttp、Volley 或您自己的实现时。
   * 在某些情况下，使用 {@link #prepend(Class,Class, ModelLoaderFactory)} 或 {@link #append(Class, Class, ModelLoaderFactory)} 可能仍会许 Glide 的默认网络库运行。
   * 使用此方法将确保只有您的网络库会运行，否则请求将失败。
   *
   * <p> 要完全替换 Glide 的默认行为并确保它不会运行，请使用 replace() 。 replace() 会删除所有处理给定模型和数据类的 ModelLoaders ，然后添加您的 ModelLoader 。
   * replace() 在使用 OkHttp 或 Volley 等库替换 Glide 的网络逻辑时特别有用，在这种情况下，您要确保只使用 OkHttp 或 Volley。
   *
   * @param modelClass 模型类（例如 URL、文件路径）。
   * @param dataClass 数据类（例如 {@link java.io.InputStream}、{@link java.io.FileDescriptor}）。
   * @see #prepend(Class, Class, ModelLoaderFactory)
   * @see #append(Class, Class, ModelLoaderFactory)
   */
  @NonNull
  public <Model, Data> Registry replace(
      @NonNull Class<Model> modelClass,
      @NonNull Class<Data> dataClass,
      @NonNull ModelLoaderFactory<? extends Model, ? extends Data> factory) {
    modelLoaderRegistry.replace(modelClass, dataClass, factory);
    return this;
  }

  /**
   * 根据给定的数据类型、资源类型和转码类型，获取一个加载路径（LoadPath）
   */
  @Nullable
  public <Data, TResource, Transcode> LoadPath<Data, TResource, Transcode> getLoadPath(
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull Class<Transcode> transcodeClass) {
    LoadPath<Data, TResource, Transcode> result = loadPathCache.get(dataClass, resourceClass, transcodeClass);
    if (loadPathCache.isEmptyLoadPath(result)) {
      return null;
    } else if (result == null) {
      List<DecodePath<Data, TResource, Transcode>> decodePaths = getDecodePaths(dataClass, resourceClass, transcodeClass);
      // 可能无法从给定的数据类解码或转码为所需的类型。
      if (decodePaths.isEmpty()) {
        result = null;
      } else {
        // 一个封装了解码路径列表的对象，它表示从数据到转码类型的完整加载过程。包括 数据类，资源类，转码后的类, 解码类
        result = new LoadPath<>(dataClass, resourceClass, transcodeClass, decodePaths, throwableListPool);
      }
      loadPathCache.put(dataClass, resourceClass, transcodeClass, result);
    }
    return result;
  }

  /**
   * 构建并返回所有从数据源到目标转码类型的解码路径，确保能够处理不同的数据源和目标类型的转换。
   * 返回一条从数据源（dataClass）到目标转码类型（transcodeClass）的解码路径
   */
  @NonNull
  private <Data, TResource, Transcode> List<DecodePath<Data, TResource, Transcode>> getDecodePaths(
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull Class<Transcode> transcodeClass) {
    List<DecodePath<Data, TResource, Transcode>> decodePaths = new ArrayList<>();
    // 根据资源解码器表拿到所有能够将 dataClass 解码为 resourceClass（或其子类）的资源类型列表
    List<Class<TResource>> registeredResourceClasses = decoderRegistry.getResourceClasses(dataClass, resourceClass);
    // 遍历全部的资源类型列表
    for (Class<TResource> registeredResourceClass : registeredResourceClasses) {
      // 查找所有能够将解码后的资源类型转码为 transcodeClass 的转码类型
      List<Class<Transcode>> registeredTranscodeClasses = transcoderRegistry.getTranscodeClasses(registeredResourceClass, transcodeClass);
      for (Class<Transcode> registeredTranscodeClass : registeredTranscodeClasses) {
        // 将数据从 dataClass 解析并最终转码为 transcodeClass
        List<ResourceDecoder<Data, TResource>> decoders = decoderRegistry.getDecoders(dataClass, registeredResourceClass);
        ResourceTranscoder<TResource, Transcode> transcoder = transcoderRegistry.get(registeredResourceClass, registeredTranscodeClass);
        // path 表示一条从 dataClass 到 transcodeClass 的转换路径，并将这些路径添加到 decodePaths 列表中
        @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
        DecodePath<Data, TResource, Transcode> path = new DecodePath<>(dataClass, registeredResourceClass, registeredTranscodeClass, decoders, transcoder,
            throwableListPool);
        decodePaths.add(path);
      }
    }
    return decodePaths;
  }

  /**
   * 拿到当前所需的资源(模型类，数据类，转码器)
   */
  @NonNull
  public <Model, TResource, Transcode> List<Class<?>> getRegisteredResourceClasses(
      @NonNull Class<Model> modelClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull Class<Transcode> transcodeClass
  ) {
    List<Class<?>> result = modelToResourceClassCache.get(modelClass, resourceClass, transcodeClass);
    if (result == null) {
      result = new ArrayList<>();
      // 根据模型加载表拿到资源类
      List<Class<?>> dataClasses = modelLoaderRegistry.getDataClasses(modelClass);
      for (Class<?> dataClass : dataClasses) {
        // 根据资源解码器表拿到所有能够将 dataClass 解码为 resourceClass（或其子类）的 资源类型列表
        List<? extends Class<?>> registeredResourceClasses = decoderRegistry.getResourceClasses(dataClass, resourceClass);
        // 遍历全部的资源类型列表
        for (Class<?> registeredResourceClass : registeredResourceClasses) {
          // 根据转码器表，拿到所有 registeredResourceClass 能转码成 transcodeClass 的转码器
          List<Class<Transcode>> registeredTranscodeClasses = transcoderRegistry.getTranscodeClasses(registeredResourceClass, transcodeClass);
          // 如果存在从 dataClass 到 transcodeClass 的转码路径，则说明该 registeredResourceClass 可用
          if (!registeredTranscodeClasses.isEmpty() && !result.contains(registeredResourceClass)) {
            // 添加可以作为中间 resourceClass 的类型（最终可能解码成这个类，然后再转码）
            result.add(registeredResourceClass);
          }
        }
      }
      modelToResourceClassCache.put(modelClass, resourceClass, transcodeClass, Collections.unmodifiableList(result));
    }
    return result;
  }

  public boolean isResourceEncoderAvailable(@NonNull Resource<?> resource) {
    return resourceEncoderRegistry.get(resource.getResourceClass()) != null;
  }

  @NonNull
  public <X> ResourceEncoder<X> getResultEncoder(@NonNull Resource<X> resource)
      throws NoResultEncoderAvailableException {
    ResourceEncoder<X> resourceEncoder = resourceEncoderRegistry.get(resource.getResourceClass());
    if (resourceEncoder != null) {
      return resourceEncoder;
    }
    throw new NoResultEncoderAvailableException(resource.getResourceClass());
  }

  @NonNull
  @SuppressWarnings("unchecked")
  public <X> Encoder<X> getSourceEncoder(@NonNull X data) throws NoSourceEncoderAvailableException {
    Encoder<X> encoder = encoderRegistry.getEncoder((Class<X>) data.getClass());
    if (encoder != null) {
      return encoder;
    }
    throw new NoSourceEncoderAvailableException(data.getClass());
  }

  @NonNull
  public <X> DataRewinder<X> getRewinder(@NonNull X data) {
    return dataRewinderRegistry.build(data);
  }

  /**
   * 拿到全部 Model 可用的 (Model, Data, ModelLoaderFactory)(模型，数据，模型工厂)
   */
  @NonNull
  public <Model> List<ModelLoader<Model, ?>> getModelLoaders(@NonNull Model model) {
    return modelLoaderRegistry.getModelLoaders(model);
  }

  @NonNull
  public List<ImageHeaderParser> getImageHeaderParsers() {
    List<ImageHeaderParser> result = imageHeaderParserRegistry.getParsers();
    if (result.isEmpty()) {
      throw new NoImageHeaderParserException();
    }
    return result;
  }

  /**
   * Thrown when no {@link com.bumptech.glide.load.model.ModelLoader} is registered for a given
   * model class.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class NoModelLoaderAvailableException extends MissingComponentException {

    public NoModelLoaderAvailableException(@NonNull Object model) {
      super("Failed to find any ModelLoaders registered for model class: " + model.getClass());
    }

    public <M> NoModelLoaderAvailableException(
        @NonNull M model, @NonNull List<ModelLoader<M, ?>> matchingButNotHandlingModelLoaders) {
      super(
          "Found ModelLoaders for model class: "
              + matchingButNotHandlingModelLoaders
              + ", but none that handle this specific model instance: "
              + model);
    }

    public NoModelLoaderAvailableException(
        @NonNull Class<?> modelClass, @NonNull Class<?> dataClass) {
      super("Failed to find any ModelLoaders for model: " + modelClass + " and data: " + dataClass);
    }
  }

  /**
   * Thrown when no {@link ResourceEncoder} is registered for a given resource class.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class NoResultEncoderAvailableException extends MissingComponentException {
    public NoResultEncoderAvailableException(@NonNull Class<?> resourceClass) {
      super(
          "Failed to find result encoder for resource class: "
              + resourceClass
              + ", you may need to consider registering a new Encoder for the requested type or"
              + " DiskCacheStrategy.DATA/DiskCacheStrategy.NONE if caching your transformed"
              + " resource is unnecessary.");
    }
  }

  /**
   * Thrown when no {@link Encoder} is registered for a given data class.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class NoSourceEncoderAvailableException extends MissingComponentException {
    public NoSourceEncoderAvailableException(@NonNull Class<?> dataClass) {
      super("Failed to find source encoder for data class: " + dataClass);
    }
  }

  /**
   * Thrown when some necessary component is missing for a load.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class MissingComponentException extends RuntimeException {
    public MissingComponentException(@NonNull String message) {
      super(message);
    }
  }

  /**
   * Thrown when no {@link ImageHeaderParser} is registered.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static final class NoImageHeaderParserException extends MissingComponentException {
    public NoImageHeaderParserException() {
      super("Failed to find image header parser.");
    }
  }
}
