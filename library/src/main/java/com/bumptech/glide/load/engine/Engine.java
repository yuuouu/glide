package com.bumptech.glide.load.engine;

import android.util.Log;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Pools;
import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.EngineResource.ResourceListener;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.Executors;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.pool.FactoryPools;
import java.util.Map;
import java.util.concurrent.Executor;

/** 负责开始负载并管理主动和缓存的资源。 */
public class Engine implements EngineJobListener, MemoryCache.ResourceRemovedListener, EngineResource.ResourceListener {
  private static final String TAG = "Engine";
  private static final int JOB_POOL_SIZE = 150;
  private static final boolean VERBOSE_IS_LOGGABLE = Log.isLoggable(TAG, Log.VERBOSE);
  private final Jobs jobs;
  private final EngineKeyFactory keyFactory;
  private final MemoryCache cache;
  private final EngineJobFactory engineJobFactory;
  private final ResourceRecycler resourceRecycler;
  private final LazyDiskCacheProvider diskCacheProvider;
  private final DecodeJobFactory decodeJobFactory;
  private final ActiveResources activeResources;

  public Engine(
      MemoryCache memoryCache,
      DiskCache.Factory diskCacheFactory,
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      boolean isActiveResourceRetentionAllowed) {
    this(
        memoryCache,
        diskCacheFactory,
        diskCacheExecutor,
        sourceExecutor,
        sourceUnlimitedExecutor,
        animationExecutor,
        /* jobs= */ null,
        /* keyFactory= */ null,
        /* activeResources= */ null,
        /* engineJobFactory= */ null,
        /* decodeJobFactory= */ null,
        /* resourceRecycler= */ null,
        isActiveResourceRetentionAllowed);
  }

  @VisibleForTesting
  Engine(
      MemoryCache cache,
      DiskCache.Factory diskCacheFactory,
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      Jobs jobs,
      EngineKeyFactory keyFactory,
      ActiveResources activeResources,
      EngineJobFactory engineJobFactory,
      DecodeJobFactory decodeJobFactory,
      ResourceRecycler resourceRecycler,
      boolean isActiveResourceRetentionAllowed) {
    this.cache = cache;
    this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

    if (activeResources == null) {
      activeResources = new ActiveResources(isActiveResourceRetentionAllowed);
    }
    this.activeResources = activeResources;
    activeResources.setListener(this);

    if (keyFactory == null) {
      keyFactory = new EngineKeyFactory();
    }
    this.keyFactory = keyFactory;

    if (jobs == null) {
      jobs = new Jobs();
    }
    this.jobs = jobs;

    if (engineJobFactory == null) {
      engineJobFactory =
          new EngineJobFactory(
              diskCacheExecutor,
              sourceExecutor,
              sourceUnlimitedExecutor,
              animationExecutor,
              /* engineJobListener= */ this,
              /* resourceListener= */ this);
    }
    this.engineJobFactory = engineJobFactory;

    if (decodeJobFactory == null) {
      decodeJobFactory = new DecodeJobFactory(diskCacheProvider);
    }
    this.decodeJobFactory = decodeJobFactory;

    if (resourceRecycler == null) {
      resourceRecycler = new ResourceRecycler();
    }
    this.resourceRecycler = resourceRecycler;

    cache.setResourceRemovedListener(this);
  }

  /**
   * 根据给定的参数启动加载。
   *
   * <p>必须在主线程上调用。
   *
   * <p>任何请求的流程如下：
   *
   * <ul>
   * <li>检查当前正在使用的资源集合，如果存在则返回活动资源，
   * 并将任何新近不活动的资源移入内存缓存。
   * <li>检查内存缓存，如果存在则提供缓存的资源。
   * <li>检查当前正在进行的加载集合，如果存在，则将 cb 添加到正在进行的加载中。
   * <li>启动新的加载。
   * </ul>
   *
   * <p>活动资源是指已提供给至少一个请求且尚未
   * 释放的资源。一旦资源的所有使用者都释放了该资源，该资源就会
   * 进入缓存。如果资源从缓存中返回给新的使用者，则会将其重新添加到
   * 活动资源中。如果资源从缓存中被移除，其资源将被回收并
   * 尽可能重复使用，然后丢弃该资源。没有严格要求
   * 消费者释放其资源，因此活动资源会被弱持有。
   *
   * @param width 所需资源的目标宽度（以像素为单位）。
   * @param height 所需资源的目标高度（以像素为单位）。
   * @param cb 加载完成后将调用的回调函数。
   */
  public <R> LoadStatus load(GlideContext glideContext, Object model, Key signature, int width, int height, Class<?> resourceClass, Class<R> transcodeClass,
      Priority priority, DiskCacheStrategy diskCacheStrategy, Map<Class<?>, Transformation<?>> transformations, boolean isTransformationRequired,
      boolean isScaleOnlyOrNoTransform, Options options, boolean isMemoryCacheable, boolean useUnlimitedSourceExecutorPool, boolean useAnimationPool,
      boolean onlyRetrieveFromCache, ResourceCallback cb, Executor callbackExecutor) {
    long startTime = VERBOSE_IS_LOGGABLE ? LogTime.getLogTime() : 0;

    /**
     * 重点！！
     * key 是通过 {@link EngineKeyFactory#buildKey(Object, Key, int, int, Map, Class, Class, Options)} 创建的。
     * 实际为该对象的 {@link EngineKey#hashCode()}
     */
    EngineKey key = keyFactory.buildKey(model, signature, width, height, transformations, resourceClass, transcodeClass, options);

    EngineResource<?> memoryResource;
    synchronized (this) {
      // 缓存加载图片的入口
      memoryResource = loadFromMemory(key, isMemoryCacheable, startTime);

      if (memoryResource == null) {
        // 如果缓存为空，则启动一个线程去加载图片
        return waitForExistingOrStartNewJob(glideContext, model, signature, width, height, resourceClass, transcodeClass, priority, diskCacheStrategy,
            transformations, isTransformationRequired, isScaleOnlyOrNoTransform, options, isMemoryCacheable, useUnlimitedSourceExecutorPool,
            useAnimationPool, onlyRetrieveFromCache, cb, callbackExecutor, key, startTime);
      }
    }

    // 避免在持有引擎锁的情况下回调，否则调用者更容易死锁
    cb.onResourceReady(memoryResource, DataSource.MEMORY_CACHE, /* isLoadedFromAlternateCacheKey= */ false);
    return null;
  }

  private <R> LoadStatus waitForExistingOrStartNewJob(GlideContext glideContext, Object model, Key signature, int width, int height, Class<?> resourceClass,
      Class<R> transcodeClass, Priority priority, DiskCacheStrategy diskCacheStrategy, Map<Class<?>, Transformation<?>> transformations,
      boolean isTransformationRequired, boolean isScaleOnlyOrNoTransform, Options options, boolean isMemoryCacheable, boolean useUnlimitedSourceExecutorPool,
      boolean useAnimationPool, boolean onlyRetrieveFromCache, ResourceCallback cb, Executor callbackExecutor, EngineKey key, long startTime) {
    // 查看当前任务是否已经在执行中
    EngineJob<?> current = jobs.get(key, onlyRetrieveFromCache);
    if (current != null) {
      current.addCallback(cb, callbackExecutor);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Added to existing load", startTime, key);
      }
      return new LoadStatus(cb, current);
    }
    EngineJob<R> engineJob = engineJobFactory.build(key, isMemoryCacheable, useUnlimitedSourceExecutorPool, useAnimationPool, onlyRetrieveFromCache);
    DecodeJob<R> decodeJob = decodeJobFactory.build(glideContext, model, key, signature, width, height, resourceClass, transcodeClass, priority,
        diskCacheStrategy, transformations, isTransformationRequired, isScaleOnlyOrNoTransform, onlyRetrieveFromCache, options, engineJob);

    jobs.put(key, engineJob);
    engineJob.addCallback(cb, callbackExecutor);
    engineJob.start(decodeJob);
    if (VERBOSE_IS_LOGGABLE) {
      logWithTimeAndKey("Started new load", startTime, key);
    }
    return new LoadStatus(cb, engineJob);
  }

  @Keep
  @Nullable
  private EngineResource<?> loadFromMemory(EngineKey key, boolean isMemoryCacheable, long startTime) {
    if (!isMemoryCacheable) {
      return null;
    }
    // 从活动内存中加载图片资源
    EngineResource<?> active = loadFromActiveResources(key);
    if (active != null) {
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from active resources", startTime, key);
      }
      return active;
    }

    // 从内存缓存中加载图片资源
    EngineResource<?> cached = loadFromCache(key);
    if (cached != null) {
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from cache", startTime, key);
      }
      return cached;
    }
    return null;
  }

  private static void logWithTimeAndKey(String log, long startTime, Key key) {
    Log.v(TAG, log + " in " + LogTime.getElapsedMillis(startTime) + "ms, key: " + key);
  }

  @Nullable
  private EngineResource<?> loadFromActiveResources(Key key) {
    EngineResource<?> active = activeResources.get(key);
    if (active != null) {
      // 命中活动资源时，增加引用计数
      active.acquire();
    }
    return active;
  }

  private EngineResource<?> loadFromCache(Key key) {
    EngineResource<?> cached = getEngineResourceFromCache(key);
    if (cached != null) {
      // 命中内存缓存时，增加引用计数
      cached.acquire();
      // 并将其放入活动内存中
      activeResources.activate(key, cached);
    }
    return cached;
  }

  private EngineResource<?> getEngineResourceFromCache(Key key) {
    // 命中内存缓存时，从内存缓存中获取图片资源后移除，放进活动内存中
    Resource<?> cached = cache.remove(key);

    final EngineResource<?> result;
    if (cached == null) {
      result = null;
    } else if (cached instanceof EngineResource) {
      // 保存对象分配，如果命中 EngineResource (典型情况)
      result = (EngineResource<?>) cached;
    } else {
      result = new EngineResource<>(cached,/* isMemoryCacheable= */ true,/* isRecyclable= */ true, key,/* listener= */ this);
    }
    return result;
  }

  public void release(Resource<?> resource) {
    if (resource instanceof EngineResource) {
      ((EngineResource<?>) resource).release();
    } else {
      throw new IllegalArgumentException("Cannot release anything but an EngineResource");
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public synchronized void onEngineJobComplete(EngineJob<?> engineJob, Key key, EngineResource<?> resource) {
    // 无效资源表明负载失败，通常是由于例外
    if (resource != null && resource.isMemoryCacheable()) {
      activeResources.activate(key, resource);
    }

    jobs.removeIfCurrent(key, engineJob);
  }

  @Override
  public synchronized void onEngineJobCancelled(EngineJob<?> engineJob, Key key) {
    jobs.removeIfCurrent(key, engineJob);
  }

  @Override
  public void onResourceRemoved(@NonNull final Resource<?> resource) {
    // Avoid deadlock with RequestManagers when recycling triggers recursive clear() calls.
    // See b/145519760.
    resourceRecycler.recycle(resource, /* forceNextFrame= */ true);
  }

  @Override
  public void onResourceReleased(Key cacheKey, EngineResource<?> resource) {
    activeResources.deactivate(cacheKey);
    if (resource.isMemoryCacheable()) {
      cache.put(cacheKey, resource);
    } else {
      resourceRecycler.recycle(resource, /* forceNextFrame= */ false);
    }
  }

  public void clearDiskCache() {
    diskCacheProvider.getDiskCache().clear();
  }

  @VisibleForTesting
  public void shutdown() {
    engineJobFactory.shutdown();
    diskCacheProvider.clearDiskCacheIfCreated();
    activeResources.shutdown();
  }

  /**
   * Allows a request to indicate it no longer is interested in a given load.
   *
   * <p>Non-final for mocking.
   */
  public class LoadStatus {
    private final EngineJob<?> engineJob;
    private final ResourceCallback cb;

    LoadStatus(ResourceCallback cb, EngineJob<?> engineJob) {
      this.cb = cb;
      this.engineJob = engineJob;
    }

    public void cancel() {
      // Acquire the Engine lock so that a new request can't get access to a particular EngineJob
      // just after the EngineJob has been cancelled. Without this lock, we'd allow new requests
      // to find the cancelling EngineJob in our Jobs data structure. With this lock, the EngineJob
      // is both cancelled and removed from Jobs atomically.
      synchronized (Engine.this) {
        engineJob.removeCallback(cb);
      }
    }
  }

  private static class LazyDiskCacheProvider implements DecodeJob.DiskCacheProvider {

    private final DiskCache.Factory factory;
    private volatile DiskCache diskCache;

    LazyDiskCacheProvider(DiskCache.Factory factory) {
      this.factory = factory;
    }

    @VisibleForTesting
    synchronized void clearDiskCacheIfCreated() {
      if (diskCache == null) {
        return;
      }
      diskCache.clear();
    }

    @Override
    public DiskCache getDiskCache() {
      if (diskCache == null) {
        synchronized (this) {
          if (diskCache == null) {
            diskCache = factory.build();
          }
          if (diskCache == null) {
            diskCache = new DiskCacheAdapter();
          }
        }
      }
      return diskCache;
    }
  }

  @VisibleForTesting
  static class DecodeJobFactory {
    @Synthetic final DecodeJob.DiskCacheProvider diskCacheProvider;

    @Synthetic
    final Pools.Pool<DecodeJob<?>> pool =
        FactoryPools.threadSafe(
            JOB_POOL_SIZE,
            new FactoryPools.Factory<DecodeJob<?>>() {
              @Override
              public DecodeJob<?> create() {
                return new DecodeJob<>(diskCacheProvider, pool);
              }
            });

    private int creationOrder;

    DecodeJobFactory(DecodeJob.DiskCacheProvider diskCacheProvider) {
      this.diskCacheProvider = diskCacheProvider;
    }

    @SuppressWarnings("unchecked")
    <R> DecodeJob<R> build(GlideContext glideContext, Object model, EngineKey loadKey, Key signature, int width, int height, Class<?> resourceClass, Class<R> transcodeClass,
                           Priority priority, DiskCacheStrategy diskCacheStrategy, Map<Class<?>, Transformation<?>> transformations,
                           boolean isTransformationRequired, boolean isScaleOnlyOrNoTransform, boolean onlyRetrieveFromCache, Options options, DecodeJob.Callback<R> callback) {
      DecodeJob<R> result = Preconditions.checkNotNull((DecodeJob<R>) pool.acquire());
      return result.init(glideContext, model, loadKey, signature, width, height, resourceClass, transcodeClass, priority, diskCacheStrategy,
              transformations, isTransformationRequired, isScaleOnlyOrNoTransform, onlyRetrieveFromCache, options, callback, creationOrder++);
    }
  }

  @VisibleForTesting
  static class EngineJobFactory {
    @Synthetic final GlideExecutor diskCacheExecutor;
    @Synthetic final GlideExecutor sourceExecutor;
    @Synthetic final GlideExecutor sourceUnlimitedExecutor;
    @Synthetic final GlideExecutor animationExecutor;
    @Synthetic final EngineJobListener engineJobListener;
    @Synthetic final ResourceListener resourceListener;

    @Synthetic
    final Pools.Pool<EngineJob<?>> pool =
        FactoryPools.threadSafe(
            JOB_POOL_SIZE,
            new FactoryPools.Factory<EngineJob<?>>() {
              @Override
              public EngineJob<?> create() {
                return new EngineJob<>(
                    diskCacheExecutor,
                    sourceExecutor,
                    sourceUnlimitedExecutor,
                    animationExecutor,
                    engineJobListener,
                    resourceListener,
                    pool);
              }
            });

    EngineJobFactory(
        GlideExecutor diskCacheExecutor,
        GlideExecutor sourceExecutor,
        GlideExecutor sourceUnlimitedExecutor,
        GlideExecutor animationExecutor,
        EngineJobListener engineJobListener,
        ResourceListener resourceListener) {
      this.diskCacheExecutor = diskCacheExecutor;
      this.sourceExecutor = sourceExecutor;
      this.sourceUnlimitedExecutor = sourceUnlimitedExecutor;
      this.animationExecutor = animationExecutor;
      this.engineJobListener = engineJobListener;
      this.resourceListener = resourceListener;
    }

    @VisibleForTesting
    void shutdown() {
      Executors.shutdownAndAwaitTermination(diskCacheExecutor);
      Executors.shutdownAndAwaitTermination(sourceExecutor);
      Executors.shutdownAndAwaitTermination(sourceUnlimitedExecutor);
      Executors.shutdownAndAwaitTermination(animationExecutor);
    }

    @SuppressWarnings("unchecked")
    <R> EngineJob<R> build(
        Key key,
        boolean isMemoryCacheable,
        boolean useUnlimitedSourceGeneratorPool,
        boolean useAnimationPool,
        boolean onlyRetrieveFromCache) {
      EngineJob<R> result = Preconditions.checkNotNull((EngineJob<R>) pool.acquire());
      return result.init(
          key,
          isMemoryCacheable,
          useUnlimitedSourceGeneratorPool,
          useAnimationPool,
          onlyRetrieveFromCache);
    }
  }
}
