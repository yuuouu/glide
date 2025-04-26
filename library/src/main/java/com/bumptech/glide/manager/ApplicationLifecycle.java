package com.bumptech.glide.manager;

import android.util.Log;

import androidx.annotation.NonNull;

/**
 * A {@link com.bumptech.glide.manager.Lifecycle} implementation for tracking and notifying
 * listeners of {@link android.app.Application} lifecycle events.
 *
 * <p>Since there are essentially no {@link android.app.Application} lifecycle events, this class
 * simply defaults to notifying new listeners that they are started.
 */
class ApplicationLifecycle implements Lifecycle {
  @Override
  public void addListener(@NonNull LifecycleListener listener) {
    Log.e("yuu", "ApplicationLifecycle addListener");
    listener.onStart();
  }

  @Override
  public void removeListener(@NonNull LifecycleListener listener) {
    // Do nothing.
    Log.e("yuu", "ApplicationLifecycle removeListener ");
  }
}
