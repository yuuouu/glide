package com.bumptech.glide.manager;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.util.Util;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A class for tracking, canceling, and restarting in progress, completed, and failed requests.
 *
 * <p>This class is not thread safe and must be accessed on the main thread.
 */
public class RequestTracker {
  private static final String TAG = "RequestTracker";

  // Most requests will be for views and will therefore be held strongly (and safely) by the view
  // via the tag. However, a user can always pass in a different type of target which may end up not
  // being strongly referenced even though the user still would like the request to finish. Weak
  // references are therefore only really functional in this context for view targets. Despite the
  // side affects, WeakReferences are still essentially required. A user can always make repeated
  // requests into targets other than views, or use an activity manager in a fragment pager where
  // holding strong references would steadily leak bitmaps and/or views.

  /**
   * 大多数请求都是针对视图的，因此视图会通过标签将其强引用（且安全地）持有。
   * 但是，用户始终可以传入不同类型的目标，即使用户仍然希望请求完成，最终也可能不会被强引用。
   * 因此，弱引用实际上只在视图目标的上下文中起作用。尽管存在副作用，弱引用仍然是必需的。
   * 用户始终可以向视图以外的目标发出重复请求，或者在片段分页器中使用活动管理器，因为持有强引用会导致位图和/或视图的泄漏。
   */
  private final Set<Request> requests = Collections.newSetFromMap(new WeakHashMap<Request, Boolean>());
  // A set of requests that have not completed and are queued to be run again. We use this list to
  // maintain hard references to these requests to ensure that they are not garbage collected
  // before they start running or while they are paused. See #346.
  /**
   * 一组尚未完成且正在排队等待再次运行的请求。
   * 我们使用此列表维护对这些请求的硬引用，以确保它们在开始运行之前或暂停期间不会被垃圾回收。请参阅 #346。
   */
  private final Set<Request> pendingRequests = new HashSet<>();

  private boolean isPaused;

  /**
   * Starts tracking the given request.
   */
  public void runRequest(@NonNull Request request) {
    requests.add(request);
    if (!isPaused) {
      request.begin();
    } else {
      request.clear();
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Paused, delaying request");
      }
      pendingRequests.add(request);
    }
  }

  @VisibleForTesting
  void addRequest(Request request) {
    requests.add(request);
  }

  /**
   * Stops tracking the given request, clears, and recycles it, and returns {@code true} if the
   * request was removed or invalid or {@code false} if the request was not found.
   */
  public boolean clearAndRemove(@Nullable Request request) {
    if (request == null) {
      // If the Request is null, the request is already cleared and we don't need to search further
      // for its owner.
      return true;
    }
    boolean isOwnedByUs = requests.remove(request);
    // Avoid short circuiting.
    isOwnedByUs = pendingRequests.remove(request) || isOwnedByUs;
    if (isOwnedByUs) {
      request.clear();
    }
    return isOwnedByUs;
  }

  /**
   * Returns {@code true} if requests are currently paused, and {@code false} otherwise.
   */
  public boolean isPaused() {
    return isPaused;
  }

  /**
   * Stops any in progress requests.
   */
  public void pauseRequests() {
    isPaused = true;
    for (Request request : Util.getSnapshot(requests)) {
      if (request.isRunning()) {
        // Avoid clearing parts of requests that may have completed (thumbnails) to avoid blinking
        // in the UI, while still making sure that any in progress parts of requests are immediately
        // stopped.
        request.pause();
        pendingRequests.add(request);
      }
    }
  }

  /**
   * Stops any in progress requests and releases bitmaps associated with completed requests.
   */
  public void pauseAllRequests() {
    isPaused = true;
    for (Request request : Util.getSnapshot(requests)) {
      // TODO(judds): Failed requests return false from isComplete(). They're still restarted in
      //  resumeRequests, but they're not cleared here. We should probably clear all requests here?
      if (request.isRunning() || request.isComplete()) {
        request.clear();
        pendingRequests.add(request);
      }
    }
  }

  /**
   * Starts any not yet completed or failed requests.
   */
  public void resumeRequests() {
    isPaused = false;
    for (Request request : Util.getSnapshot(requests)) {
      // We don't need to check for cleared here. Any explicit clear by a user will remove the
      // Request from the tracker, so the only way we'd find a cleared request here is if we cleared
      // it. As a result it should be safe for us to resume cleared requests.
      if (!request.isComplete() && !request.isRunning()) {
        request.begin();
      }
    }
    pendingRequests.clear();
  }

  /**
   * Cancels all requests and clears their resources.
   *
   * <p>After this call requests cannot be restarted.
   */
  public void clearRequests() {
    for (Request request : Util.getSnapshot(requests)) {
      // It's unsafe to recycle the Request here because we don't know who might else have a
      // reference to it.
      clearAndRemove(request);
    }
    pendingRequests.clear();
  }

  /**
   * Restarts failed requests and cancels and restarts in progress requests.
   */
  public void restartRequests() {
    for (Request request : Util.getSnapshot(requests)) {
      if (!request.isComplete() && !request.isCleared()) {
        request.clear();
        if (!isPaused) {
          request.begin();
        } else {
          // Ensure the request will be restarted in onResume.
          pendingRequests.add(request);
        }
      }
    }
  }

  @Override
  public String toString() {
    return super.toString() + "{numRequests=" + requests.size() + ", isPaused=" + isPaused + "}";
  }
}
