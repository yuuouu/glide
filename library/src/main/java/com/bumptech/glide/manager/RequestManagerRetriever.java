package com.bumptech.glide.manager;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.bitmap.HardwareConfigState;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Util;
import java.util.Collection;
import java.util.Map;

/**
 * 用于创建新的 {@link com.bumptech.glide.RequestManager}
 * 或从 Activity 和 Fragment 中检索现有的静态方法的集合。
 */
public class RequestManagerRetriever implements Handler.Callback {
  @VisibleForTesting static final String FRAGMENT_TAG = "com.bumptech.glide.manager";
  // application 级别的 RequestManager
  private volatile RequestManager applicationManager;
  // 其它级别的 RequestManager 通过工厂模式来创建
  private final RequestManagerFactory factory;

  // Objects used to find Fragments and Activities containing views.
  /**
   * 用于查找包含 views 的 Activities 和 Fragments 的对象。
   */
  private final ArrayMap<View, Fragment> tempViewToSupportFragment = new ArrayMap<>();
  // This is really misplaced here, but to put it anywhere else means duplicating all of the
  // Fragment/Activity extraction logic that already exists here. It's gross, but less likely to
  // break.
  /**
   * 在 Android9(P) 的系统上 && 非 applicationManager 的 manager
   * 绘制出第一帧的时候解除硬件加速位图的阻塞
   * 应该是为了兼容 GPU 绘制
   * <br>
   * 通过 {@link FirstFrameWaiter#registerSelf(Activity)} 执行 {@link HardwareConfigState#unblockHardwareBitmaps}
   */
  private final FrameWaiter frameWaiter;

  /**
   *非 applicationManager 的 manager 生命周期管理全部在这里
   */
  private final LifecycleRequestManagerRetriever lifecycleRequestManagerRetriever;

  public RequestManagerRetriever(@Nullable RequestManagerFactory factory) {
    this.factory = factory != null ? factory : DEFAULT_FACTORY;
    lifecycleRequestManagerRetriever = new LifecycleRequestManagerRetriever(this.factory);
    frameWaiter = buildFrameWaiter();
  }

  private static FrameWaiter buildFrameWaiter() {
    if (!HardwareConfigState.HARDWARE_BITMAPS_SUPPORTED || !HardwareConfigState.BLOCK_HARDWARE_BITMAPS_WHEN_GL_CONTEXT_MIGHT_NOT_BE_INITIALIZED) {
      return new DoNothingFirstFrameWaiter();
    }
    return new FirstFrameWaiter();
  }

  @NonNull
  private RequestManager getApplicationManager(@NonNull Context context) {
    if (applicationManager == null) {
      synchronized (this) {
        if (applicationManager == null) {
          // 通常情况下，pause/resume 是由我们添加到 Fragment 或 Activity 中的 Fragment 负责的
          // 当 Context 是 Application 或者处于后台线程时，由于附加到应用程序的管理器不会接收生命周期事件
          // 因此我们必须手动创建 ApplicationLifecycle 强制管理器启动并恢复
          Glide glide = Glide.get(context.getApplicationContext());
          applicationManager = factory.build(glide, new ApplicationLifecycle(), new EmptyRequestManagerTreeNode(), context.getApplicationContext());
        }
      }
    }
    return applicationManager;
  }

  @NonNull
  public RequestManager get(@NonNull Context context) {
    if (context == null) {
      throw new IllegalArgumentException("You cannot start a load on a null Context");
    } else if (Util.isOnMainThread() && !(context instanceof Application)) {
      if (context instanceof FragmentActivity) {
        return get((FragmentActivity) context);
      } else if (context instanceof ContextWrapper
          // 仅在BaseContext具有非NULL应用程序上下文的情况下拆开ContextWrapper
          // Context#createPackageContext 可能返回一个没有 Application 实例的 Context
          // 在这种情况下，可以使用 ContextWrapper 来附加一个
          && ((ContextWrapper) context).getBaseContext().getApplicationContext() != null) {
        return get(((ContextWrapper) context).getBaseContext());
      }
    }
    return getApplicationManager(context);
  }

  @NonNull
  public RequestManager get(@NonNull FragmentActivity activity) {
    if (Util.isOnBackgroundThread()) {
      // 后台线程会有潜在的内存泄漏和生命周期管理问题
      return get(activity.getApplicationContext());
    }
    assertNotDestroyed(activity);
    frameWaiter.registerSelf(activity);
    boolean isActivityVisible = isActivityVisible(activity);
    Glide glide = Glide.get(activity.getApplicationContext());
    return lifecycleRequestManagerRetriever.getOrCreate(activity, glide, activity.getLifecycle(), activity.getSupportFragmentManager(), isActivityVisible);
  }

  @NonNull
  public RequestManager get(@NonNull Fragment fragment) {
    Preconditions.checkNotNull(fragment.getContext(), "You cannot start a load on a fragment before it is attached or after it is destroyed");
    if (Util.isOnBackgroundThread()) {
      // 在后台线程获取Context会有潜在的内存泄漏和生命周期管理问题
      return get(fragment.getContext().getApplicationContext());
    }
    // 在某些特殊情况下，Fragment 可能没有被 Activity 托管。
    // 我们对此无能为力。大多数应用都会以标准 Activity 启动。
    // 如果我们暂时没有注册第一个帧等待器，后果不会很严重，只是会占用一些额外的内存。
    if (fragment.getActivity() != null) {
      frameWaiter.registerSelf(fragment.getActivity());
    }
    FragmentManager fm = fragment.getChildFragmentManager();
    Context context = fragment.getContext();
    Glide glide = Glide.get(context.getApplicationContext());
    return lifecycleRequestManagerRetriever.getOrCreate(context, glide, fragment.getLifecycle(), fm, fragment.isVisible());
  }

  /**
   * @deprecated This is identical to calling {@link #get(Context)} with the application context.
   * Use androidx Activities instead (ie {@link FragmentActivity}, or {@link androidx.appcompat.app.AppCompatActivity}).
   */
  @Deprecated
  @NonNull
  public RequestManager get(@NonNull Activity activity) {
    return get(activity.getApplicationContext());
  }

  @NonNull
  public RequestManager get(@NonNull View view) {
    if (Util.isOnBackgroundThread()) {
      // 在后台线程获取Context会有潜在的内存泄漏和生命周期管理问题
      return get(view.getContext().getApplicationContext());
    }

    Preconditions.checkNotNull(view);
    Preconditions.checkNotNull(view.getContext(),"Unable to obtain a request manager for a view without a Context");
    Activity activity = findActivity(view.getContext());
    // View可能是其他地方传过来的，例如 Service
    if (activity == null) {
      return get(view.getContext().getApplicationContext());
    }

    // 支持 Fragment。尽管用户可能将不支持的 Fragment 附加到 FragmentActivity，但在 8.0 之前，搜索不支持的 Fragment 的成本非常高，
    // 这种情况应该很少见，因此我们更倾向于直接回退到 Activity。
    if (activity instanceof FragmentActivity) {
      Fragment fragment = findSupportFragment(view, (FragmentActivity) activity);
      return fragment != null ? get(fragment) : get((FragmentActivity) activity);
    }
    return get(view.getContext().getApplicationContext());
  }

  private static void findAllSupportFragmentsWithViews(
      @Nullable Collection<Fragment> topLevelFragments, @NonNull Map<View, Fragment> result) {
    if (topLevelFragments == null) {
      return;
    }
    for (Fragment fragment : topLevelFragments) {
      // getFragment()s in the support FragmentManager may contain null values, see #1991.
      if (fragment == null || fragment.getView() == null) {
        continue;
      }
      result.put(fragment.getView(), fragment);
      findAllSupportFragmentsWithViews(fragment.getChildFragmentManager().getFragments(), result);
    }
  }

  @Nullable
  private Fragment findSupportFragment(@NonNull View target, @NonNull FragmentActivity activity) {
    tempViewToSupportFragment.clear();
    findAllSupportFragmentsWithViews(
        activity.getSupportFragmentManager().getFragments(), tempViewToSupportFragment);
    Fragment result = null;
    View activityRoot = activity.findViewById(android.R.id.content);
    View current = target;
    while (!current.equals(activityRoot)) {
      result = tempViewToSupportFragment.get(current);
      if (result != null) {
        break;
      }
      if (current.getParent() instanceof View) {
        current = (View) current.getParent();
      } else {
        break;
      }
    }

    tempViewToSupportFragment.clear();
    return result;
  }

  @Nullable
  private static Activity findActivity(@NonNull Context context) {
    if (context instanceof Activity) {
      return (Activity) context;
    } else if (context instanceof ContextWrapper) {
      return findActivity(((ContextWrapper) context).getBaseContext());
    } else {
      return null;
    }
  }

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  private static void assertNotDestroyed(@NonNull Activity activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
      throw new IllegalArgumentException("You cannot start a load for a destroyed activity");
    }
  }

  /**
   * @deprecated This is equivalent to calling {@link #get(Context)} with the application context.
   *     Use androidx fragments instead: {@link Fragment}.
   */
  @Deprecated
  @NonNull
  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  public RequestManager get(@NonNull android.app.Fragment fragment) {
    if (fragment.getActivity() == null) {
      throw new IllegalArgumentException(
          "You cannot start a load on a fragment before it is attached");
    }
    return get(fragment.getActivity().getApplicationContext());
  }

  private static boolean isActivityVisible(Context context) {
    // This is a poor heuristic, but it's about all we have. We'd rather err on the side of visible
    // and start requests than on the side of invisible and ignore valid requests.
    Activity activity = findActivity(context);
    return activity == null || !activity.isFinishing();
  }

  /**
   * @deprecated This method is no longer called by Glide or provides any functionality and it will
   * be removed in the future. Retained for now to preserve backwards compatibility.
   */
  @Deprecated
  @SuppressWarnings("PMD.CollapsibleIfStatements")
  @Override
  public boolean handleMessage(Message message) {
    return false;
  }

  /**
   * Used internally to create {@link RequestManager}s.
   */
  public interface RequestManagerFactory {
    @NonNull
    RequestManager build(
        @NonNull Glide glide,
        @NonNull Lifecycle lifecycle,
        @NonNull RequestManagerTreeNode requestManagerTreeNode,
        @NonNull Context context);
  }

  private static final RequestManagerFactory DEFAULT_FACTORY =
      new RequestManagerFactory() {
        @NonNull
        @Override
        public RequestManager build(
            @NonNull Glide glide,
            @NonNull Lifecycle lifecycle,
            @NonNull RequestManagerTreeNode requestManagerTreeNode,
            @NonNull Context context) {
          return new RequestManager(glide, lifecycle, requestManagerTreeNode, context);
        }
      };
}
