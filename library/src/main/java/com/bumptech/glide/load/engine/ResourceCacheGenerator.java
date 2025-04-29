package com.bumptech.glide.load.engine;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.pool.GlideTrace;
import java.io.File;
import java.util.List;

/**
 * 资源缓存生成器，用于从磁盘中的资源缓存目录读取已缓存好的资源文件（通常是解码过的Bitmap）。
 * 生成 {@link com.bumptech.glide.load.data.DataFetcher DataFetchers}
 *
 */
class ResourceCacheGenerator implements DataFetcherGenerator, DataFetcher.DataCallback<Object> {

  // 回调接口
  private final FetcherReadyCallback cb;

  /**
   * 包含了请求过程中需要用到的各种参数，比如请求的url、资源类型、缓存策略等。
   * <p>
   * DecodeHelper是贯穿整个加载流程的核心工具类。
   *
   * @see DecodeHelper
   */
  private final DecodeHelper<?> helper;

  // 当前正在遍历的SourceKey列表的索引位置
  private int sourceIdIndex;
  // 当前正在遍历的ResourceClass列表索引，初始值设为-1，表示尚未开始。
  private int resourceClassIndex = -1;
  private Key sourceKey;
  /**
   * 针对cacheFile文件，匹配到的可以进行加载的ModelLoader列表。
   * <p>
   * 每个ModelLoader负责将一个File转成特定格式的数据（如InputStream、Bitmap等）。
   *
   * @see ModelLoader
   */
  private List<ModelLoader<File, ?>> modelLoaders;
  // 当前正在遍历的ModelLoader列表索引
  private int modelLoaderIndex;
  // 当前加载操作的数据封装体。内部包含了真正的DataFetcher，以及加载参数。
  private volatile LoadData<?> loadData;
  // PMD is wrong here, this File must be an instance variable because it may be used across
  // multiple calls to startNext.
  /**
   * 当前正在读取的缓存文件。
   * <p>
   * 注意：由于loadData可能跨线程访问，所以cacheFile必须是实例变量，不能局部变量。
   */
  @SuppressWarnings("PMD.SingularField")
  private File cacheFile;
  /**
   * 当前组合生成的资源缓存Key。
   * <p>
   * ResourceCacheKey是根据SourceKey + ResourceClass + TranscodeClass等组合生成的Key，
   * 用于唯一定位磁盘缓存中资源文件。
   *
   * @see ResourceCacheKey
   */
  private ResourceCacheKey currentKey;

  ResourceCacheGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.helper = helper;
    this.cb = cb;
  }

  // See TODO below.
  @SuppressWarnings("PMD.CollapsibleIfStatements")
  @Override
  public boolean startNext() {
    GlideTrace.beginSection("ResourceCacheGenerator.startNext");
    try {
      List<Key> sourceIds = helper.getCacheKeys();
      if (sourceIds.isEmpty()) {
        return false;
      }
      /**
       *
       * @see com.bumptech.glide.Registry#getRegisteredResourceClasses(Class, Class, Class)
       */
      List<Class<?>> resourceClasses = helper.getRegisteredResourceClasses();
      if (resourceClasses.isEmpty()) {
        if (File.class.equals(helper.getTranscodeClass())) {
          return false;
        }
        throw new IllegalStateException("Failed to find any load path from " + helper.getModelClass() + " to " + helper.getTranscodeClass());
      }
      while (modelLoaders == null || !hasNextModelLoader()) {
        resourceClassIndex++;
        if (resourceClassIndex >= resourceClasses.size()) {
          sourceIdIndex++;
          if (sourceIdIndex >= sourceIds.size()) {
            // 如果是第一次请求，那么就会在这里返回，后面的就不会去执行了
            return false;
          }
          resourceClassIndex = 0;
        }

        Key sourceId = sourceIds.get(sourceIdIndex);
        Class<?> resourceClass = resourceClasses.get(resourceClassIndex);
        Transformation<?> transformation = helper.getTransformation(resourceClass);
        // PMD.AvoidInstantiatingObjectsInLoops 每次迭代都是比较昂贵的，我们只运行到第一次成功。
        // 在最坏的情况下，循环只运行有限次数的迭代，大约为 10-20 次。
        // 假设有缓存，那么就需要构建一个ResourceCacheKey
        currentKey = new ResourceCacheKey(helper.getArrayPool(), sourceId, helper.getSignature(), helper.getWidth(), helper.getHeight(), transformation,
                resourceClass, helper.getOptions());
        // 从磁盘中找到key对应的文件
        cacheFile = helper.getDiskCache().get(currentKey);
        if (cacheFile != null) {
          sourceKey = sourceId;
          modelLoaders = helper.getModelLoaders(cacheFile);
          modelLoaderIndex = 0;
        }
      }
      // 找到缓存文件后，就会执行到这里
      loadData = null;
      boolean started = false;
      while (!started && hasNextModelLoader()) {
        // 遍历全部的ModelLoader, 直到可以匹配
        ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
        loadData = modelLoader.buildLoadData(cacheFile, helper.getWidth(), helper.getHeight(), helper.getOptions());
        if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
          started = true;
          loadData.fetcher.loadData(helper.getPriority(), this);
        }
      }

      return started;
    } finally {
      GlideTrace.endSection();
    }
  }

  private boolean hasNextModelLoader() {
    return modelLoaderIndex < modelLoaders.size();
  }

  @Override
  public void cancel() {
    LoadData<?> local = loadData;
    if (local != null) {
      local.fetcher.cancel();
    }
  }

  @Override
  public void onDataReady(Object data) {
    cb.onDataFetcherReady(sourceKey, data, loadData.fetcher, DataSource.RESOURCE_DISK_CACHE, currentKey);
  }

  @Override
  public void onLoadFailed(@NonNull Exception e) {
    cb.onDataFetcherFailed(currentKey, e, loadData.fetcher, DataSource.RESOURCE_DISK_CACHE);
  }
}
