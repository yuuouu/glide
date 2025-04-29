package com.bumptech.glide.load.engine;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.pool.GlideTrace;
import java.io.File;
import java.util.List;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from cache files
 * containing original unmodified source data.
 */
class DataCacheGenerator implements DataFetcherGenerator, DataFetcher.DataCallback<Object> {

  private final List<Key> cacheKeys;
  private final DecodeHelper<?> helper;
  private final FetcherReadyCallback cb;

  private int sourceIdIndex = -1;
  private Key sourceKey;
  private List<ModelLoader<File, ?>> modelLoaders;
  private int modelLoaderIndex;
  private volatile LoadData<?> loadData;
  // PMD is wrong here, this File must be an instance variable because it may be used across
  // multiple calls to startNext.
  @SuppressWarnings("PMD.SingularField")
  private File cacheFile;

  DataCacheGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this(helper.getCacheKeys(), helper, cb);
  }

  // 在某些情况下，我们可能想要加载特定的缓存键（从写入缓存的源加载时），因此我们接受键列表，而不是仅从助手中获取列表。
  DataCacheGenerator(List<Key> cacheKeys, DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.cacheKeys = cacheKeys;
    this.helper = helper;
    this.cb = cb;
  }

  @Override
  public boolean startNext() {
    GlideTrace.beginSection("DataCacheGenerator.startNext");
    try {
      while (modelLoaders == null || !hasNextModelLoader()) {
        sourceIdIndex++;
        if (sourceIdIndex >= cacheKeys.size()) {
          return false;
        }

        Key sourceId = cacheKeys.get(sourceIdIndex);
        // PMD.AvoidInstantiatingObjectsInLoops The loop iterates a limited number of times
        // and the actions it performs are much more expensive than a single allocation.
        @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
        Key originalKey = new DataCacheKey(sourceId, helper.getSignature());
        cacheFile = helper.getDiskCache().get(originalKey);
        if (cacheFile != null) {
          this.sourceKey = sourceId;
          // 获取所有可能的数据加载器
          modelLoaders = helper.getModelLoaders(cacheFile);
          modelLoaderIndex = 0;
        }
      }

      loadData = null;
      boolean started = false;
      // 遍历所有的数据加载器
      while (!started && hasNextModelLoader()) {
        ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
        // 创建数据加载任务
        loadData = modelLoader.buildLoadData(cacheFile, helper.getWidth(), helper.getHeight(), helper.getOptions());
        if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
          started = true;
          // 启动数据加载
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
    cb.onDataFetcherReady(sourceKey, data, loadData.fetcher, DataSource.DATA_DISK_CACHE, sourceKey);
  }

  @Override
  public void onLoadFailed(@NonNull Exception e) {
    cb.onDataFetcherFailed(sourceKey, e, loadData.fetcher, DataSource.DATA_DISK_CACHE);
  }
}
