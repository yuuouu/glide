package com.bumptech.glide.load.engine;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.DataFetcher.DataCallback;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import java.io.IOException;
import java.util.Collections;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from original source data
 * using registered {@link com.bumptech.glide.load.model.ModelLoader ModelLoaders} and the model
 * provided for the load.
 *
 * <p>Depending on the disk cache strategy, source data may first be written to disk and then loaded
 * from the cache file rather than returned directly.
 *
 * <p>This object may be used by multiple threads, but only one at a time. It is not safe to access
 * this object on multiple threads concurrently.
 */
class SourceGenerator implements DataFetcherGenerator, DataFetcherGenerator.FetcherReadyCallback {
  private static final String TAG = "SourceGenerator";

  private final DecodeHelper<?> helper;
  private final FetcherReadyCallback cb;

  private volatile int loadDataListIndex;
  private volatile DataCacheGenerator sourceCacheGenerator;
  private volatile Object dataToCache;
  private volatile ModelLoader.LoadData<?> loadData;
  private volatile DataCacheKey originalKey;

  SourceGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.helper = helper;
    this.cb = cb;
  }


  /**
   * 启动SourceGenerator的数据获取流程。不支持并发访问。
   *
   * <p>
   * 如果有待缓存的数据(dataToCache)，优先执行cacheData流程：
   * <br>
   * - 尝试将data写入磁盘缓存
   * <br>
   * - 如果写入失败，直接返回true，表示当前处理结束，继续DecodeJob下一步
   * <br>
   * - 如果写入成功，继续尝试从缓存加载（sourceCacheGenerator）
   *
   * <p>
   * 如果有sourceCacheGenerator（即磁盘缓存读取器），优先用它执行startNext()：
   * <br>
   * - 如果startNext()返回true，表示当前还有数据要处理，直接返回
   * <br>
   * - 如果没有数据了，清空sourceCacheGenerator，继续后续流程
   *
   * <p>
   * 如果以上都没有，则进入新的LoadData尝试阶段：
   * <br>
   * - 遍历loadData列表（由ModelLoader生成）
   * <br>
   * - 检查loadData是否合法（满足可以缓存或可以加载的条件）
   * <br>
   * - 调用startNextLoad(loadData)开始加载源数据
   *
   * <p>
   * 整体流程总结：
   * <br>
   * 1. 先缓存 ➔ 2. 再从缓存读 ➔ 3. 最后重新拉源数据
   *
   * @return true表示启动了新的加载流程，false表示没有可用的数据
   */
  @SuppressWarnings({"NonAtomicOperationOnVolatileField", "NonAtomicVolatileUpdate"})
  @Override
  public boolean startNext() {
    // 1. 如果有上一次准备要缓存到磁盘的数据，优先处理
    if (dataToCache != null) {
      Object data = dataToCache;
      dataToCache = null;
      try {
        boolean isDataInCache = cacheData(data);
        if (!isDataInCache) {
          // 虽然写磁盘缓存失败，但 cacheData() 里调用了 onDataFetcherReady ,不用继续执行
          return true;
        }
        // 写缓存成功，下面会走sourceCacheGenerator，从缓存里读
      } catch (IOException e) {
        // 写缓存出错，打印日志，继续后续流程
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Failed to properly rewind or write data to cache", e);
        }
      }
    }

    // 2. 在数据缓存 DataCacheGenerator 中有缓存的话直接使用
    if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
      return true;
    }
    // 否则清空，准备重新加载
    sourceCacheGenerator = null;

    // 3. 重新准备LoadData，从 ModelLoader 生成的列表中取
    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      // loadDataListIndex是遍历索引
      loadData = helper.getLoadData().get(loadDataListIndex++);
      if (loadData != null &&
          // 要么能缓存，要么存在解码路径
          (helper.getDiskCacheStrategy().isDataCacheable(loadData.fetcher.getDataSource()) || helper.hasLoadPath(loadData.fetcher.getDataClass()))) {
        started = true;
        startNextLoad(loadData);
      }
    }
    return started;
  }


  private void startNextLoad(final LoadData<?> toStart) {
    // 这里是实际加载
    loadData.fetcher.loadData(helper.getPriority(), new DataCallback<Object>() {
      @Override
      public void onDataReady(@Nullable Object data) {
        if (isCurrentRequest(toStart)) {
          onDataReadyInternal(toStart, data);
        }
      }

      @Override
      public void onLoadFailed(@NonNull Exception e) {
        if (isCurrentRequest(toStart)) {
          onLoadFailedInternal(toStart, e);
        }
      }
    });
  }

  // We want reference equality explicitly to make sure we ignore results from old requests.
  @SuppressWarnings({"PMD.CompareObjectsWithEquals", "WeakerAccess"})
  @Synthetic
  boolean isCurrentRequest(LoadData<?> requestLoadData) {
    LoadData<?> currentLoadData = loadData;
    return currentLoadData != null && currentLoadData == requestLoadData;
  }

  private boolean hasNextModelLoader() {
    return loadDataListIndex < helper.getLoadData().size();
  }

  /**
   * 如果我们能够缓存数据并尝试直接从缓存中解码数据，则返回 {@code true}；
   * 如果我们无法缓存数据并尝试从源中解码，则返回 {@code false}。
   */
  private boolean cacheData(Object dataToCache) throws IOException {
    long startTime = LogTime.getLogTime();
    boolean isLoadingFromSourceData = false;
    try {
      DataRewinder<Object> rewinder = helper.getRewinder(dataToCache);
      Object data = rewinder.rewindAndGet();
      Encoder<Object> encoder = helper.getSourceEncoder(data);
      DataCacheWriter<Object> writer = new DataCacheWriter<>(encoder, data, helper.getOptions());
      // 查看是否存在数据缓存
      DataCacheKey newOriginalKey = new DataCacheKey(loadData.sourceKey, helper.getSignature());
      DiskCache diskCache = helper.getDiskCache();
      diskCache.put(newOriginalKey, writer);
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Finished encoding source to cache" + ", key: " + newOriginalKey + ", data: " + dataToCache + ", encoder: " + encoder + ", duration: "
            + LogTime.getElapsedMillis(startTime));
      }

      if (diskCache.get(newOriginalKey) != null) {
        originalKey = newOriginalKey;
        sourceCacheGenerator = new DataCacheGenerator(Collections.singletonList(loadData.sourceKey), helper, this);
        // We were able to write the data to cache.
        return true;
      } else {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Attempt to write: " + originalKey + ", data: " + dataToCache + " to the disk" + " cache failed, maybe the disk cache is disabled?"
              + " Trying to decode the data directly...");
        }

        isLoadingFromSourceData = true;
        // 虽然写磁盘缓存失败，但数据是有的，可以继续走下一步解码流程！
        cb.onDataFetcherReady(loadData.sourceKey, rewinder.rewindAndGet(), loadData.fetcher, loadData.fetcher.getDataSource(), loadData.sourceKey);
      }
      // We failed to write the data to cache.
      return false;
    } finally {
      if (!isLoadingFromSourceData) {
        loadData.fetcher.cleanup();
      }
    }
  }

  @Override
  public void cancel() {
    LoadData<?> local = loadData;
    if (local != null) {
      local.fetcher.cancel();
    }
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void onDataReadyInternal(LoadData<?> loadData, Object data) {
    DiskCacheStrategy diskCacheStrategy = helper.getDiskCacheStrategy();
    if (data != null && diskCacheStrategy.isDataCacheable(loadData.fetcher.getDataSource())) {
      dataToCache = data;
      // 我们可能会被调用到其他线程。在执行任何操作之前，我们应该重新安排时间，回到 Glide 的线程上。
      // 这样，一旦我们回到 Glide 的线程上，我们就会再次被调用，并将检索到的数据写入缓存。
      cb.reschedule();
    } else {
      cb.onDataFetcherReady(loadData.sourceKey, data, loadData.fetcher, loadData.fetcher.getDataSource(), originalKey);
    }
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void onLoadFailedInternal(LoadData<?> loadData, @NonNull Exception e) {
    cb.onDataFetcherFailed(originalKey, e, loadData.fetcher, loadData.fetcher.getDataSource());
  }

  @Override
  public void reschedule() {
    // 我们不希望这种情况发生，尽管如果我们需要的话，我们可以委托给 callback
    throw new UnsupportedOperationException();
  }

  // Called from source cache generator.
  @Override
  public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher, DataSource dataSource, Key attemptedKey) {
    // 该数据获取器将从文件加载并提供错误的数据源，因此请使用原始数据获取器的数据源进行覆盖
    cb.onDataFetcherReady(sourceKey, data, fetcher, loadData.fetcher.getDataSource(), sourceKey);
  }

  @Override
  public void onDataFetcherFailed(Key sourceKey, Exception e, DataFetcher<?> fetcher, DataSource dataSource) {
    cb.onDataFetcherFailed(sourceKey, e, fetcher, loadData.fetcher.getDataSource());
  }
}
