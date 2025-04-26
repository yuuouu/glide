package com.bumptech.glide;

import static com.bumptech.glide.request.RequestOptions.decodeTypeOf;
import static com.bumptech.glide.request.RequestOptions.diskCacheStrategyOf;
import static com.bumptech.glide.request.RequestOptions.skipMemoryCacheOf;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import androidx.annotation.CheckResult;
import androidx.annotation.DrawableRes;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.manager.ConnectivityMonitor;
import com.bumptech.glide.manager.ConnectivityMonitorFactory;
import com.bumptech.glide.manager.Lifecycle;
import com.bumptech.glide.manager.LifecycleListener;
import com.bumptech.glide.manager.RequestManagerTreeNode;
import com.bumptech.glide.manager.RequestTracker;
import com.bumptech.glide.manager.TargetTracker;
import com.bumptech.glide.request.BaseRequestOptions;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.SingleRequest;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import com.bumptech.glide.util.pool.GlideTrace;
import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * 用于管理和启动 Glide 请求的类。
 * 可以使用 Activity、Fragment 和连接生命周期事件来智能地停止、启动和重新启动请求。
 * 您可以通过实例化新对象来获取请求，或者为了利用内置的 Activity 和 Fragment 生命周期处理功能，
 * 请在 Fragment 或 Activity 中使用静态 Glide.load 方法。
 *
 * @see Glide#with(android.app.Activity)
 * @see Glide#with(androidx.fragment.app.FragmentActivity)
 * @see Glide#with(android.app.Fragment)
 * @see Glide#with(androidx.fragment.app.Fragment)
 * @see Glide#with(Context)
 */
public class RequestManager
    implements ComponentCallbacks2, LifecycleListener, ModelTypes<RequestBuilder<Drawable>> {
  private static final RequestOptions DECODE_TYPE_BITMAP = decodeTypeOf(Bitmap.class).lock();
  private static final RequestOptions DECODE_TYPE_GIF = decodeTypeOf(GifDrawable.class).lock();
  private static final RequestOptions DOWNLOAD_ONLY_OPTIONS = diskCacheStrategyOf(DiskCacheStrategy.DATA).priority(Priority.LOW).skipMemoryCache(true);

  protected final Glide glide;
  protected final Context context;

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  final Lifecycle lifecycle;

  /**
   * <p>职责分离原则设计，RequestTracker 负责跟踪和管理由该 RequestManager 发起的所有 Request 对象
   *
   * <p>Request 是单个加载请求的抽象接口，代表一个具体的资源加载过程（开始、暂停、停止、回收）
   * <p>开始准备资源 {@link SingleRequest#begin()} <br>
   * 准备方法里调用263行 {@code target.onLoadStarted(getPlaceholderDrawable())} 通知给 {@link Target#onLoadStarted(Drawable)}
   *
   * <p>资源准备完成后在这个方法 {@link SingleRequest#onResourceReady(Resource, Object, DataSource, boolean)} 里调用
   * 650行 {@code listener.onResourceReady(result, model, target, dataSource, isFirstResource)} 同步给 {@link RequestListener#onResourceReady(Object, Object, Target, DataSource, boolean)} 和
   * 667行 {@code target.onResourceReady(result, animation)} 同步给 {@link Target#onResourceReady(Object, Transition)}
   *
   * <p>类似的，如果资源准备失败则调用 {@link SingleRequest#onLoadFailed(GlideException)}
   *
   * @see Request
   * @see SingleRequest#begin()
   * @see SingleRequest#onResourceReady(Resource, Object, DataSource, boolean)
   * @see SingleRequest#onLoadFailed(GlideException)
   */
  @GuardedBy("this")
  private final RequestTracker requestTracker;

  /**
   * 职责分离原则设计，TargetTracker 专门用于集中管理和通知所有关联的 Target 的生命周期事件
   * <p> Target 是一个核心接口，负责在资源加载过程、加载完成、加载失败等事件中执行任务
   * <br>
   * 所有通过 Glide 加载的图片（无论是显示到 ImageView、写入文件，还是自定义处理）最终都会通过 Target 接收结果
   * @see Target
   */
  @GuardedBy("this")
  private final TargetTracker targetTracker = new TargetTracker();

  /**
   * 在生命周期改变时寻找所有受影响的 RequestManager 节点，以便进行统一处理
   *
   * @see com.bumptech.glide.manager.LifecycleRequestManagerRetriever.SupportRequestManagerTreeNode#getDescendants()
   **/
  @GuardedBy("this")
  private final RequestManagerTreeNode treeNode;

  private final Runnable addSelfToLifecycle =
      new Runnable() {
        @Override
        public void run() {
          lifecycle.addListener(RequestManager.this);
        }
      };
  /**
   * 网络连接状态的监视器
   * <p>
   * 1.在创建的时候判断是否有网络权限 {@link com.bumptech.glide.manager.DefaultConnectivityMonitorFactory#build(Context, ConnectivityMonitor.ConnectivityListener)}
   * <br>
   * 2.有网络权限的话注册网络监听服务 {@link com.bumptech.glide.manager.SingletonConnectivityReceiver#SingletonConnectivityReceiver(Context)}}
   * <br>
   * 3.通过 {@link RequestManagerConnectivityListener#onConnectivityChanged} 回调给 {@link #requestTracker}
   **/
  private final ConnectivityMonitor connectivityMonitor;

  /**
   * 保存所有的图片监听加载请求状态，每个监听器都会起作用
   * <br>
   * RequestListener 中仅有 onLoadFailed 和 onResourceReady 方法
   *
   * @see #addDefaultRequestListener(RequestListener)
   */
  private final CopyOnWriteArrayList<RequestListener<Object>> defaultRequestListeners;

  /**
   * 配置此次加载请求参数，默认为全局配置 {@link com.bumptech.glide.module.AppGlideModule#applyOptions}
   * 冲突时覆盖对应属性
   * <br>
   * 外部调用时使用 {@link RequestBuilder#apply(BaseRequestOptions)}
   * <br>
   * 默认配置使用 {@link #applyDefaultRequestOptions(RequestOptions)}
   *
   * @see BaseRequestOptions#apply(BaseRequestOptions)
   */
  @GuardedBy("this")
  private RequestOptions requestOptions;

  private boolean pauseAllRequestsOnTrimMemoryModerate;

  private boolean clearOnStop;

  public RequestManager(@NonNull Glide glide, @NonNull Lifecycle lifecycle,
      @NonNull RequestManagerTreeNode treeNode, @NonNull Context context) {
    this(glide, lifecycle, treeNode, new RequestTracker(), glide.getConnectivityMonitorFactory(), context);
  }

  // Our usage is safe here.
  @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
  RequestManager(Glide glide, Lifecycle lifecycle, RequestManagerTreeNode treeNode, RequestTracker requestTracker, ConnectivityMonitorFactory factory, Context context) {
    this.glide = glide;
    this.lifecycle = lifecycle;
    this.treeNode = treeNode;
    this.requestTracker = requestTracker;
    this.context = context;
    connectivityMonitor = factory.build(context.getApplicationContext(), new RequestManagerConnectivityListener(requestTracker));
    Log.e("yuu", "RequestManager: requestTracker=" + requestTracker.hashCode() + " targetTracker=" + targetTracker.hashCode());
    // 顺序很重要，这可能会被下面的听众取消注册，所以我们需要确保先注册以防止断言和内存泄漏。
    glide.registerRequestManager(this);

    // 如果我们是应用级请求管理器，我们可能会在后台线程中创建。
    // 在这种情况下，我们不能冒险同步暂停或恢复请求，因此我们通过延迟将自身添加为生命周期监听器（通过将其发布到主线程）来解决这个问题。
    // 这应该是完全安全的。
    if (Util.isOnBackgroundThread()) {
      Util.postOnUiThread(addSelfToLifecycle);
    } else {
      lifecycle.addListener(this);
    }
    lifecycle.addListener(connectivityMonitor);

    defaultRequestListeners = new CopyOnWriteArrayList<>(glide.getGlideContext().getDefaultRequestListeners());
    setRequestOptions(glide.getGlideContext().getDefaultRequestOptions());
  }

  protected synchronized void setRequestOptions(@NonNull RequestOptions toSet) {
    requestOptions = toSet.clone().autoClone();
  }

  private synchronized void updateRequestOptions(@NonNull RequestOptions toUpdate) {
    requestOptions = requestOptions.apply(toUpdate);
  }

  /**
   * 使用新的配置更新此请求管理器启动的所有加载的 {@link RequestOptions}
   *
   * <p>此处提供的配置优先级比 {@link GlideBuilder#setDefaultRequestOptions(RequestOptions)} 默认配置高。
   * 可以应用多组选项。如果存在冲突，则最后的配置生效。不冲突则叠加
   * <p>请注意，此方法不会改变默认配置
   * <p>修改后的选项将仅应用于调用此方法后启动的加载。
   *
   * @return This request manager.
   * @see RequestBuilder#apply(BaseRequestOptions)
   */
  @NonNull
  public synchronized RequestManager applyDefaultRequestOptions(@NonNull RequestOptions requestOptions) {
    updateRequestOptions(requestOptions);
    return this;
  }

  /**
   * Replaces the default {@link RequestOptions} for all loads started with this request manager
   * with the given {@link RequestOptions}.
   *
   * <p>The {@link RequestOptions} provided here replace those that have been previously provided
   * via this method, {@link GlideBuilder#setDefaultRequestOptions(RequestOptions)}, and {@link
   * #applyDefaultRequestOptions(RequestOptions)}.
   *
   * <p>Subsequent calls to {@link #applyDefaultRequestOptions(RequestOptions)} will not mutate the
   * {@link RequestOptions} provided here. Instead the manager will create a clone of these options
   * and mutate the clone.
   *
   * @return This request manager.
   * @see #applyDefaultRequestOptions(RequestOptions)
   */
  @NonNull
  public synchronized RequestManager setDefaultRequestOptions(@NonNull RequestOptions requestOptions) {
    setRequestOptions(requestOptions);
    return this;
  }

  /**
   * Clear all resources when onStop() from {@link LifecycleListener} is called.
   *
   * @return This request manager.
   */
  @NonNull
  public synchronized RequestManager clearOnStop() {
    clearOnStop = true;
    return this;
  }

  /**
   * 添加一个默认的 {@link RequestListener}，它将添加到此 {@link RequestManager} 启动的每个 {@link Request} 中。
   *
   * <p>可以在 {@link RequestManager} 作用域内添加单个或多个 {@link RequestListener}。
   * 按照其添加的顺序调用。即使先前的 {@link RequestListener#onLoadFailed(GlideException, Object, Target, boolean)} 或
   * {@link RequestListener#onResourceReady(Object, Object, Target, DataSource, boolean)} 中返回 {@code true}，
   * 也不会阻止后续的 {@link RequestListener} 被调用。
   *
   * <p>由于 Glide 请求可以针对任意数量的单独资源类型启动，因此此处添加的任何监听器都必须接受
   * {@link RequestListener#onResourceReady(Object, Object, Target, DataSource, boolean)}
   * 中的任何通用资源类型。如果您必须根据资源类型设置监听器的行为，则需要使用 {@code instanceof} 来执行此操作。
   * 在没有先使用 {@code instanceof} 检查的情况下强制转换资源类型是不安全的。
   */
  public RequestManager addDefaultRequestListener(RequestListener<Object> requestListener) {
    defaultRequestListeners.add(requestListener);
    return this;
  }

  /**
   * If {@code true} then clear all in-progress and completed requests when the platform sends
   * {@code onTrimMemory} with level = {@code TRIM_MEMORY_MODERATE}.
   */
  public void setPauseAllRequestsOnTrimMemoryModerate(boolean pauseAllOnTrim) {
    pauseAllRequestsOnTrimMemoryModerate = pauseAllOnTrim;
  }

  /**
   * Returns true if loads for this {@link RequestManager} are currently paused.
   *
   * @see #pauseRequests()
   * @see #resumeRequests()
   */
  public synchronized boolean isPaused() {
    return requestTracker.isPaused();
  }

  /**
   * Cancels any in progress loads, but does not clear resources of completed loads.
   *
   * <p>Note #{@link #resumeRequests()} must be called for any requests made before or while the
   * manager is paused to complete. RequestManagers attached to Fragments and Activities
   * automatically resume onStart().
   *
   * @see #isPaused()
   * @see #resumeRequests()
   */
  public synchronized void pauseRequests() {
    requestTracker.pauseRequests();
  }

  /**
   * Cancels any in progress loads and clears resources of completed loads.
   *
   * <p>Note #{@link #resumeRequests()} must be called for any requests made before or while the
   * manager is paused to complete. RequestManagers attached to Fragments and Activities
   * automatically resume onStart().
   *
   * <p>This will release the memory used by completed bitmaps but leaves them in any configured
   * caches. When an #{@link android.app.Activity} receives #{@link
   * android.app.Activity#onTrimMemory(int)} at a level of #{@link
   * android.content.ComponentCallbacks2#TRIM_MEMORY_BACKGROUND} this is desirable in order to keep
   * your process alive longer.
   *
   * @see #isPaused()
   * @see #resumeRequests()
   */
  public synchronized void pauseAllRequests() {
    requestTracker.pauseAllRequests();
  }

  /**
   * Performs {@link #pauseAllRequests()} recursively for all managers that are contextually
   * descendant to this manager based on the Activity/Fragment hierarchy.
   *
   * <p>Similar to {@link #pauseRequestsRecursive()} with the exception that it also clears
   * resources of completed loads.
   */
  // Public API.
  @SuppressWarnings({"WeakerAccess", "unused"})
  public synchronized void pauseAllRequestsRecursive() {
    pauseAllRequests();
    for (RequestManager requestManager : treeNode.getDescendants()) {
      requestManager.pauseAllRequests();
    }
  }

  /**
   * Performs {@link #pauseRequests()} recursively for all managers that are contextually descendant
   * to this manager based on the Activity/Fragment hierarchy:
   *
   * <ul>
   *   <li>When pausing on an Activity all attached fragments will also get paused.
   *   <li>When pausing on an attached Fragment all descendant fragments will also get paused.
   *   <li>When pausing on a detached Fragment or the application context only the current
   *       RequestManager is paused.
   * </ul>
   *
   * <p>Note, on pre-Jelly Bean MR1 calling pause on a Fragment will not cause child fragments to
   * pause, in this case either call pause on the Activity or use a support Fragment.
   */
  // Public API.
  @SuppressWarnings({"WeakerAccess", "unused"})
  public synchronized void pauseRequestsRecursive() {
    pauseRequests();
    for (RequestManager requestManager : treeNode.getDescendants()) {
      requestManager.pauseRequests();
    }
  }

  /**
   * Restarts any loads that have not yet completed.
   *
   * @see #isPaused()
   * @see #pauseRequests()
   */
  public synchronized void resumeRequests() {
    requestTracker.resumeRequests();
  }

  /**
   * Performs {@link #resumeRequests()} recursively for all managers that are contextually
   * descendant to this manager based on the Activity/Fragment hierarchy. The hierarchical semantics
   * are identical as for {@link #pauseRequestsRecursive()}.
   */
  // Public API.
  @SuppressWarnings("unused")
  public synchronized void resumeRequestsRecursive() {
    Util.assertMainThread();
    resumeRequests();
    for (RequestManager requestManager : treeNode.getDescendants()) {
      requestManager.resumeRequests();
    }
  }

  /**
   * Lifecycle callback that registers for connectivity events (if the
   * android.permission.ACCESS_NETWORK_STATE permission is present) and restarts failed or paused
   * requests.
   */
  @Override
  public synchronized void onStart() {
    resumeRequests();
    targetTracker.onStart();
  }

  /**
   * Lifecycle callback that unregisters for connectivity events (if the
   * android.permission.ACCESS_NETWORK_STATE permission is present) and pauses in progress loads
   * and clears all resources if {@link #clearOnStop()} is called.
   */
  @Override
  public synchronized void onStop() {
    targetTracker.onStop();
    if (clearOnStop) {
      clearRequests();
    } else {
      pauseRequests();
    }
  }

  /**
   * Lifecycle callback that cancels all in progress requests and clears and recycles resources for
   * all completed requests.
   */
  @Override
  public synchronized void onDestroy() {
    Log.e("yuu ", " RequestManager onDestroy: requestTracker=" + requestTracker.hashCode());
    targetTracker.onDestroy();
    clearRequests();
    requestTracker.clearRequests();
    lifecycle.removeListener(this);
    lifecycle.removeListener(connectivityMonitor);
    Util.removeCallbacksOnUiThread(addSelfToLifecycle);
    glide.unregisterRequestManager(this);
  }

  /**
   * Attempts to always load the resource as a {@link android.graphics.Bitmap}, even if it could
   * actually be animated.
   *
   * @return A new request builder for loading a {@link android.graphics.Bitmap}
   */
  @NonNull
  @CheckResult
  public RequestBuilder<Bitmap> asBitmap() {
    return as(Bitmap.class).apply(DECODE_TYPE_BITMAP);
  }

  /**
   * Attempts to always load the resource as a {@link
   * com.bumptech.glide.load.resource.gif.GifDrawable}.
   *
   * <p>If the underlying data is not a GIF, this will fail. As a result, this should only be used
   * if the model represents an animated GIF and the caller wants to interact with the GifDrawable
   * directly. Normally using just {@link #asDrawable()} is sufficient because it will determine
   * whether or not the given data represents an animated GIF and return the appropriate {@link
   * Drawable}, animated or not, automatically.
   *
   * @return A new request builder for loading a {@link
   * com.bumptech.glide.load.resource.gif.GifDrawable}.
   */
  @NonNull
  @CheckResult
  public RequestBuilder<GifDrawable> asGif() {
    return as(GifDrawable.class).apply(DECODE_TYPE_GIF);
  }

  /**
   * Attempts to always load the resource using any registered {@link
   * com.bumptech.glide.load.ResourceDecoder}s that can decode any subclass of {@link Drawable}.
   *
   * <p>By default, may return either a {@link android.graphics.drawable.BitmapDrawable} or {@link
   * GifDrawable}, but if additional decoders are registered for other {@link Drawable} subclasses,
   * any of those subclasses may also be returned.
   *
   * @return A new request builder for loading a {@link Drawable}.
   */
  @NonNull
  @CheckResult
  public RequestBuilder<Drawable> asDrawable() {
    return as(Drawable.class);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(Bitmap)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable Bitmap bitmap) {
    return asDrawable().load(bitmap);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(Drawable)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable Drawable drawable) {
    return asDrawable().load(drawable);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(String)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable String string) {
    return asDrawable().load(string);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(Uri)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable Uri uri) {
    return asDrawable().load(uri);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(File)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable File file) {
    return asDrawable().load(file);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(Integer)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @SuppressWarnings("deprecation")
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@RawRes @DrawableRes @Nullable Integer resourceId) {
    return asDrawable().load(resourceId);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(URL)}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @SuppressWarnings("deprecation")
  @CheckResult
  @Override
  @Deprecated
  public RequestBuilder<Drawable> load(@Nullable URL url) {
    return asDrawable().load(url);
  }

  /**
   * Equivalent to calling {@link #asDrawable()} and then {@link RequestBuilder#load(byte[])}.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable byte[] model) {
    return asDrawable().load(model);
  }

  /**
   * A helper method equivalent to calling {@link #asDrawable()} and then {@link
   * RequestBuilder#load(Object)} with the given model.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  @Override
  public RequestBuilder<Drawable> load(@Nullable Object model) {
    return asDrawable().load(model);
  }

  /**
   * Attempts always load the resource into the cache and return the {@link File} containing the
   * cached source data.
   *
   * <p>This method is designed to work for remote data that is or will be cached using {@link
   * com.bumptech.glide.load.engine.DiskCacheStrategy#DATA}. As a result, specifying a {@link
   * com.bumptech.glide.load.engine.DiskCacheStrategy} on this request is generally not recommended.
   *
   * @return A new request builder for downloading content to cache and returning the cache File.
   */
  @NonNull
  @CheckResult
  public RequestBuilder<File> downloadOnly() {
    return as(File.class).apply(DOWNLOAD_ONLY_OPTIONS);
  }

  /**
   * A helper method equivalent to calling {@link #downloadOnly()} ()} and then {@link
   * RequestBuilder#load(Object)} with the given model.
   *
   * @return A new request builder for loading a {@link Drawable} using the given model.
   */
  @NonNull
  @CheckResult
  public RequestBuilder<File> download(@Nullable Object model) {
    return downloadOnly().load(model);
  }

  /**
   * Attempts to always load a {@link File} containing the resource, either using a file path
   * obtained from the media store (for local images/videos), or using Glide's disk cache (for
   * remote images/videos).
   *
   * <p>For remote content, prefer {@link #downloadOnly()}.
   *
   * @return A new request builder for obtaining File paths to content.
   */
  @NonNull
  @CheckResult
  public RequestBuilder<File> asFile() {
    return as(File.class).apply(skipMemoryCacheOf(true));
  }

  /**
   * Attempts to load the resource using any registered {@link
   * com.bumptech.glide.load.ResourceDecoder}s that can decode the given resource class or any
   * subclass of the given resource class.
   *
   * @param resourceClass The resource to decode.
   * @return A new request builder for loading the given resource class.
   */
  @NonNull
  @CheckResult
  public <ResourceType> RequestBuilder<ResourceType> as(
      @NonNull Class<ResourceType> resourceClass) {
    return new RequestBuilder<>(glide, this, resourceClass, context);
  }

  /**
   * Cancel any pending loads Glide may have for the view and free any resources that may have been
   * loaded for the view.
   *
   * <p>Note that this will only work if {@link View#setTag(Object)} is not called on this view
   * outside of Glide.
   *
   * @param view The view to cancel loads and free resources for.
   * @throws IllegalArgumentException if an object other than Glide's metadata is put as the view's
   *                                  tag.
   * @see #clear(Target)
   */
  public void clear(@NonNull View view) {
    clear(new ClearTarget(view));
  }

  /**
   * Cancel any pending loads Glide may have for the target and free any resources (such as {@link
   * Bitmap}s) that may have been loaded for the target so they may be reused.
   *
   * @param target The Target to cancel loads for.
   */
  public void clear(@Nullable final Target<?> target) {
    if (target == null) {
      return;
    }

    untrackOrDelegate(target);
  }

  private void untrackOrDelegate(@NonNull Target<?> target) {
    boolean isOwnedByUs = untrack(target);
    // We'll end up here if the Target was cleared after the RequestManager that started the request
    // is destroyed. That can happen for at least two reasons:
    // 1. We call clear() on a background thread using something other than Application Context
    // RequestManager.
    // 2. The caller retains a reference to the RequestManager after the corresponding Activity or
    // Fragment is destroyed, starts a load with it, and then clears that load with a different
    // RequestManager. Callers seem especially likely to do this in retained Fragments (#2262).
    //
    // #1 is always an error. At best the caller is leaking memory briefly in something like an
    // AsyncTask. At worst the caller is leaking an Activity or Fragment for a sustained period of
    // time if they do something like reference the Activity RequestManager in a long lived
    // background thread or task.
    //
    // #2 is always an error. Callers shouldn't be starting new loads using RequestManagers after
    // the corresponding Activity or Fragment is destroyed because retaining any reference to the
    // RequestManager leaks memory. It's possible that there's some brief period of time during or
    // immediately after onDestroy where this is reasonable, but I can't think of why.
    Request request = target.getRequest();
    if (!isOwnedByUs && !glide.removeFromManagers(target) && request != null) {
      target.setRequest(null);
      request.clear();
    }
  }

  synchronized boolean untrack(@NonNull Target<?> target) {
    Request request = target.getRequest();
    // If the Target doesn't have a request, it's already been cleared.
    if (request == null) {
      return true;
    }

    if (requestTracker.clearAndRemove(request)) {
      targetTracker.untrack(target);
      target.setRequest(null);
      return true;
    } else {
      return false;
    }
  }

  /**
   * 外部调用 {@link RequestBuilder#into(Target)} or {@link RequestBuilder#submit()} 进入内部方法
   * {@link RequestBuilder#into(Target, RequestListener, BaseRequestOptions, Executor)} 里的858行
   * {@code requestManager.track(target, request)} 跳转到此方法 {@link #track(Target, Request)}
   */
  synchronized void track(@NonNull Target<?> target, @NonNull Request request) {
    targetTracker.track(target);
    requestTracker.runRequest(request);
  }

  List<RequestListener<Object>> getDefaultRequestListeners() {
    return defaultRequestListeners;
  }

  synchronized RequestOptions getDefaultRequestOptions() {
    return requestOptions;
  }

  @NonNull
  <T> TransitionOptions<?, T> getDefaultTransitionOptions(Class<T> transcodeClass) {
    return glide.getGlideContext().getDefaultTransitionOptions(transcodeClass);
  }

  @Override
  public synchronized String toString() {
    return super.toString() + "{tracker=" + requestTracker + ", treeNode=" + treeNode + "}";
  }

  @Override
  public void onTrimMemory(int level) {
    if (level == TRIM_MEMORY_MODERATE && pauseAllRequestsOnTrimMemoryModerate) {
      pauseAllRequestsRecursive();
    }
  }

  @Override
  public void onLowMemory() {
    // Nothing to add conditionally. See Glide#onTrimMemory for unconditional behavior.
  }

  private synchronized void clearRequests() {
    for (Target<?> target : targetTracker.getAll()) {
      clear(target);
    }
    targetTracker.clear();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {}

  private class RequestManagerConnectivityListener implements ConnectivityMonitor.ConnectivityListener {
    @GuardedBy("RequestManager.this")
    private final RequestTracker requestTracker;

    RequestManagerConnectivityListener(@NonNull RequestTracker requestTracker) {
      this.requestTracker = requestTracker;
    }

    @Override
    public void onConnectivityChanged(boolean isConnected) {
      if (isConnected) {
        synchronized (RequestManager.this) {
          requestTracker.restartRequests();
        }
      }
    }
  }

  private static class ClearTarget extends CustomViewTarget<View, Object> {

    ClearTarget(@NonNull View view) {
      super(view);
    }

    @Override
    protected void onResourceCleared(@Nullable Drawable placeholder) {
      // Do nothing, we don't retain a reference to our resource.
    }

    @Override
    public void onLoadFailed(@Nullable Drawable errorDrawable) {
      // Do nothing.
    }

    @Override
    public void onResourceReady(
        @NonNull Object resource, @Nullable Transition<? super Object> transition) {
      // Do nothing.
    }
  }
}
