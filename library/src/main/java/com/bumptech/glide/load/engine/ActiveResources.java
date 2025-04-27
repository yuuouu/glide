package com.bumptech.glide.load.engine;

import android.os.Process;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.EngineResource.ResourceListener;
import com.bumptech.glide.util.Executors;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

final class ActiveResources {
  /**
   * 全局配置属性，默认为 false，设为 true 时是一个已清理但可重用的缓存
   * <br>
   * 通过 {@link GlideBuilder#setIsActiveResourceRetentionAllowed(boolean)} 设置，只能在全局配置中设置，不能通过 RequestOptions 配置
   */
  private final boolean isActiveResourceRetentionAllowed;

  /**
   *  newSingleThreadExecutor 一个线程，为了测试才搞成内部变量
   */
  private final Executor monitorClearedResourcesExecutor;

  /**
   * 缓存的资源
   * <br>
   * key 是通过 {@link EngineKeyFactory#buildKey(Object, Key, int, int, Map, Class, Class, Options)} 创建的，实际为该对象的 {@link EngineKey#hashCode()}
   * <br>
   * value 是一个弱引用类，确保当资源不被其它对象强引用时会被回收，避免出现内存泄漏。实际的存储资源 {@link ResourceWeakReference#resource}
   */
  @VisibleForTesting final Map<Key, ResourceWeakReference> activeEngineResources = new HashMap<>();

  /**
   * 一个监控通道，用来通知 Glide：某个之前中活动缓存中的资源对象，已经被 JVM 判定为可回收（弱可达），需要做后续清理
   * <br>
   * 在 {@link #cleanReferenceQueue} 监听到资源被回收时，会从队列中移除该资源，并调用 {@link #cleanupActiveReference(ResourceWeakReference)} 方法进行清理
   */
  private final ReferenceQueue<EngineResource<?>> resourceReferenceQueue = new ReferenceQueue<>();

  /**
   * 回调接口，向 {@link EngineResource} 通知资源已经被回收，需要做后续清理
   */
  private ResourceListener listener;

  private volatile boolean isShutdown;
  @Nullable private volatile DequeuedResourceCallback cb;

  ActiveResources(boolean isActiveResourceRetentionAllowed) {
    this(
        isActiveResourceRetentionAllowed,
        java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> new Thread(() -> {
                  Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                  r.run();
                }, "glide-active-resources")));
  }

  @VisibleForTesting
  ActiveResources(boolean isActiveResourceRetentionAllowed, Executor monitorClearedResourcesExecutor) {
    this.isActiveResourceRetentionAllowed = isActiveResourceRetentionAllowed;
    this.monitorClearedResourcesExecutor = monitorClearedResourcesExecutor;
    monitorClearedResourcesExecutor.execute(this::cleanReferenceQueue);
  }

  void setListener(ResourceListener listener) {
    synchronized (listener) {
      synchronized (this) {
        this.listener = listener;
      }
    }
  }

  synchronized void activate(Key key, EngineResource<?> resource) {
    ResourceWeakReference toPut = new ResourceWeakReference(key, resource, resourceReferenceQueue, isActiveResourceRetentionAllowed);
    ResourceWeakReference removed = activeEngineResources.put(key, toPut);
    if (removed != null) {
      removed.reset();
    }
  }

  synchronized void deactivate(Key key) {
    ResourceWeakReference removed = activeEngineResources.remove(key);
    if (removed != null) {
      removed.reset();
    }
  }

  @Nullable
  synchronized EngineResource<?> get(Key key) {
    ResourceWeakReference activeRef = activeEngineResources.get(key);
    if (activeRef == null) {
      return null;
    }
    EngineResource<?> active = activeRef.get();
    if (active == null) {
      // 如果资源是空的则清除当前的弱引用
      cleanupActiveReference(activeRef);
    }
    return active;
  }

  @SuppressWarnings({"WeakerAccess", "SynchronizeOnNonFinalField"})
  @Synthetic
  void cleanupActiveReference(@NonNull ResourceWeakReference ref) {
    synchronized (this) {
      activeEngineResources.remove(ref.key);
      if (!ref.isCacheable || ref.resource == null) {
        return;
      }
    }
    EngineResource<?> newResource = new EngineResource<>(ref.resource,/* isMemoryCacheable= */ true,/* isRecyclable= */ false, ref.key, listener);
    listener.onResourceReleased(ref.key, newResource);
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void cleanReferenceQueue() {
    while (!isShutdown) {
      try {
        /**
         * 如果 resourceReferenceQueue 队列里没有数据， remove() 方法里的 {@code lock.wait(time)} 会让线程无限期阻塞，而不是无限循环
         * <br>
         * 当资源不再被任何强引用持有时（包括缓存、业务、引用计数都清空），JVM GC 会自动将该资源对应的弱引用放入（相当于resourceReferenceQueue.enqueue()）
         */
        ResourceWeakReference ref = (ResourceWeakReference) resourceReferenceQueue.remove();
        cleanupActiveReference(ref);

        // 以下是测试数据
        DequeuedResourceCallback current = cb;
        if (current != null) {
          current.onResourceDequeued();
        }
        // End for testing only.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @VisibleForTesting
  void setDequeuedResourceCallback(DequeuedResourceCallback cb) {
    this.cb = cb;
  }

  @VisibleForTesting
  interface DequeuedResourceCallback {
    void onResourceDequeued();
  }

  @VisibleForTesting
  void shutdown() {
    isShutdown = true;
    if (monitorClearedResourcesExecutor instanceof ExecutorService) {
      ExecutorService service = (ExecutorService) monitorClearedResourcesExecutor;
      Executors.shutdownAndAwaitTermination(service);
    }
  }

  @VisibleForTesting
  static final class ResourceWeakReference extends WeakReference<EngineResource<?>> {
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    final Key key;

    @SuppressWarnings("WeakerAccess")
    @Synthetic
    final boolean isCacheable;

    @Nullable
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    Resource<?> resource;

    @Synthetic
    @SuppressWarnings("WeakerAccess")
    ResourceWeakReference(@NonNull Key key, @NonNull EngineResource<?> referent, @NonNull ReferenceQueue<? super EngineResource<?>> queue,
        boolean isActiveResourceRetentionAllowed) {
      super(referent, queue);
      this.key = Preconditions.checkNotNull(key);
      this.resource = referent.isMemoryCacheable() && isActiveResourceRetentionAllowed ? Preconditions.checkNotNull(referent.getResource()) : null;
      isCacheable = referent.isMemoryCacheable();
    }

    void reset() {
      resource = null;
      clear();
    }
  }
}
