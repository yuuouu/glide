package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.bumptech.glide.util.Synthetic;

/** A class that can safely recycle recursive resources. */
class ResourceRecycler {
  private boolean isRecycling;
  private final Handler handler =
      new Handler(Looper.getMainLooper(), new ResourceRecyclerCallback());

  synchronized void recycle(Resource<?> resource, boolean forceNextFrame) {
    if (isRecycling || forceNextFrame) {
      // 如果某个资源包含子资源，则释放子资源可能会导致其父资源被同步驱逐，从而导致在父资源释放其子资源时出现循环回收。
      // 发布操作可打破此循环。
      handler.obtainMessage(ResourceRecyclerCallback.RECYCLE_RESOURCE, resource).sendToTarget();
    } else {
      isRecycling = true;
      resource.recycle();
      isRecycling = false;
    }
  }

  private static final class ResourceRecyclerCallback implements Handler.Callback {
    static final int RECYCLE_RESOURCE = 1;

    @Synthetic
    ResourceRecyclerCallback() {}

    @Override
    public boolean handleMessage(Message message) {
      if (message.what == RECYCLE_RESOURCE) {
        Resource<?> resource = (Resource<?>) message.obj;
        resource.recycle();
        return true;
      }
      return false;
    }
  }
}
