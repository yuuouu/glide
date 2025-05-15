package com.bumptech.glide.load.engine;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.EncodeStrategy;

/** Set of available caching strategies for media. */
public abstract class DiskCacheStrategy {

  /**
   * 使用 {@link #DATA} 和 {@link #RESOURCE} 缓存远程数据，
   * 仅使用 {@link #RESOURCE} 缓存本地数据。
   */
  public static final DiskCacheStrategy ALL = new DiskCacheStrategy() {
        @Override
        public boolean isDataCacheable(DataSource dataSource) {
          return dataSource == DataSource.REMOTE;
        }

        @Override
        public boolean isResourceCacheable(boolean isFromAlternateCacheKey, DataSource dataSource, EncodeStrategy encodeStrategy) {
          return dataSource != DataSource.RESOURCE_DISK_CACHE && dataSource != DataSource.MEMORY_CACHE;
        }

        @Override
        public boolean decodeCachedResource() {
          return true;
        }

        @Override
        public boolean decodeCachedData() {
          return true;
        }
      };

  /**
   * 不缓存数据
   */
  public static final DiskCacheStrategy NONE = new DiskCacheStrategy() {
        @Override
        public boolean isDataCacheable(DataSource dataSource) {
          return false;
        }

        @Override
        public boolean isResourceCacheable(boolean isFromAlternateCacheKey, DataSource dataSource, EncodeStrategy encodeStrategy) {
          return false;
        }

        @Override
        public boolean decodeCachedResource() {
          return false;
        }

        @Override
        public boolean decodeCachedData() {
          return false;
        }
      };

  /**
   * 在解码之前，将数据直接写到磁盘缓存
   */
  public static final DiskCacheStrategy DATA = new DiskCacheStrategy() {
        @Override
        public boolean isDataCacheable(DataSource dataSource) {
          return dataSource != DataSource.DATA_DISK_CACHE && dataSource != DataSource.MEMORY_CACHE;
        }

        @Override
        public boolean isResourceCacheable(boolean isFromAlternateCacheKey, DataSource dataSource, EncodeStrategy encodeStrategy) {
          return false;
        }

        @Override
        public boolean decodeCachedResource() {
          return false;
        }

        @Override
        public boolean decodeCachedData() {
          return true;
        }
      };

  /**
   * 解码后，将资源写入磁盘
   */
  public static final DiskCacheStrategy RESOURCE = new DiskCacheStrategy() {
        @Override
        public boolean isDataCacheable(DataSource dataSource) {
          return false;
        }

        @Override
        public boolean isResourceCacheable(boolean isFromAlternateCacheKey, DataSource dataSource, EncodeStrategy encodeStrategy) {
          return dataSource != DataSource.RESOURCE_DISK_CACHE && dataSource != DataSource.MEMORY_CACHE;
        }

        @Override
        public boolean decodeCachedResource() {
          return true;
        }

        @Override
        public boolean decodeCachedData() {
          return false;
        }
      };

  /**
   * 尝试根据 {@link com.bumptech.glide.load.data.DataFetcher} 的数据源和 {@link com.bumptech.glide.load.ResourceEncoder} 的
   * {@link com.bumptech.glide.load.EncodeStrategy}（如果有 {@link com.bumptech.glide.load.ResourceEncoder} 可用）智能地选择策略。
   */
  public static final DiskCacheStrategy AUTOMATIC = new DiskCacheStrategy() {
        @Override
        public boolean isDataCacheable(DataSource dataSource) {
          return dataSource == DataSource.REMOTE;
        }

        @SuppressWarnings("checkstyle:UnnecessaryParentheses") // Readability
        @Override
        public boolean isResourceCacheable(boolean isFromAlternateCacheKey, DataSource dataSource, EncodeStrategy encodeStrategy) {
          return ((isFromAlternateCacheKey && dataSource == DataSource.DATA_DISK_CACHE) || dataSource == DataSource.LOCAL) && encodeStrategy == EncodeStrategy.TRANSFORMED;
        }

        @Override
        public boolean decodeCachedResource() {
          return true;
        }

        @Override
        public boolean decodeCachedData() {
          return true;
        }
      };

  /**
   * 如果此请求应缓存原始未修改的数据，则返回 true。
   *
   * @param dataSource 指示最初检索数据的位置。
   */
  public abstract boolean isDataCacheable(DataSource dataSource);

  /**
   * 如果此请求应缓存最终转换后的资源，则返回 true。
   * @param isFromAlternateCacheKey {@code true} 如果我们解码的资源是使用备用缓存键（而非主缓存键）加载的。
   * @param dataSource 指示用于解码资源的数据最初在哪里获取。
   * @param encodeStrategy {@link com.bumptech.glide.load.ResourceEncoder} 将使用 {@link EncodeStrategy} 对资源进行编码。
   */
  public abstract boolean isResourceCacheable(boolean isFromAlternateCacheKey, DataSource dataSource, EncodeStrategy encodeStrategy);

  /**
   *  如果此请求应尝试解码缓存的资源数据，则返回为 true
   */
  public abstract boolean decodeCachedResource();

  /**
   * 如果此请求应尝试解码缓存的源数据，则返回 true
   */
  public abstract boolean decodeCachedData();
}
