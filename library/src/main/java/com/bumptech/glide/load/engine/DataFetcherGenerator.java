package com.bumptech.glide.load.engine;

import androidx.annotation.Nullable;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;

/**
 * Generates a series of {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} using
 * registered {@link com.bumptech.glide.load.model.ModelLoader ModelLoaders} and a model.
 */
interface DataFetcherGenerator {
  /**
   * Called when the generator has finished loading data from a {@link
   * com.bumptech.glide.load.data.DataFetcher}.
   */
  interface FetcherReadyCallback {

    /** Requests that we call startNext() again on a Glide owned thread. */
    void reschedule();

    /**
     * Notifies the callback that the load is complete.
     *
     * @param sourceKey The id of the loaded data.
     * @param data The loaded data, or null if the load failed.
     * @param fetcher The data fetcher we attempted to load from.
     * @param dataSource The data source we were loading from.
     * @param attemptedKey The key we were loading data from (may be an alternate).
     */
    void onDataFetcherReady(
        Key sourceKey,
        @Nullable Object data,
        DataFetcher<?> fetcher,
        DataSource dataSource,
        Key attemptedKey);

    /**
     * Notifies the callback when the load fails.
     *
     * @param attemptedKey The key we were using to load (may be an alternate).
     * @param e The exception that caused the load to fail.
     * @param fetcher The fetcher we were loading from.
     * @param dataSource The data source we were loading from.
     */
    void onDataFetcherFailed(Key attemptedKey, Exception e, DataFetcher<?> fetcher, DataSource dataSource);
  }

  /**
   * 尝试启动一个新的 {@link com.bumptech.glide.load.data.DataFetcher}。
   * 返回启动结果
   */
  boolean startNext();

  /**
   *尝试取消当前正在运行的 fetcher。
   *
   * <p>这将在主线程上调用，并且应该很快完成。
   */
  void cancel();
}
