package yuu.test

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Key
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class TestActivity: AppCompatActivity() {
    private val TAG = "yuu"

    /**
     * 模拟 {@link ActiveResources#cleanReferenceQueue()} 中 ReferenceQueue.remove() 的阻塞与唤醒机制
     * 创建一个 ReferenceQueue，用来存放被 enqueue 的引用
     */
    private val resourceReferenceQueue = ReferenceQueue<EngineResource<*>>()

    // 我们可以用这个 map 来追踪所有还“激活中”的弱引用
//    val activeResources: MutableList<WeakReference<EngineResource<*>>> = mutableListOf()

    val activeResources: MutableMap<Int?, WeakReference<*>> = HashMap<Int?, WeakReference<*>>()

    // 线程池，用来执行阻塞 remove() 调用
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ResourceQueueMonitor").apply { isDaemon = true }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val imageView = findViewById<ImageView>(R.id.iv)

        Glide.with(applicationContext).load(R.mipmap.fushi).into(imageView)

        // 启动监控线程：调用 remove() 会在队列空时无限期阻塞
        executor.execute {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Log.d("WeakRefDemo", "monitorThread while")
                    // 阻塞，直到有 WeakReference 被 enqueue 到 queue
                    val ref: Reference<*> = resourceReferenceQueue.remove()
                    // 处理被回收的资源引用
                    @Suppress("UNCHECKED_CAST") val wr = ref as WeakReference<EngineResource<*>>
                    cleanupActiveReference(wr)
                }
            } catch (e: InterruptedException) {
                Log.d("WeakRefDemo", "Queue monitor interrupted, exiting")
            }
        }


        imageView.setOnClickListener {
            // 1. 创建真实资源
            val engineRes = EngineResource("DemoData")

            // 2. 包装为 WeakReference，并关联到同一个 referenceQueue
            val weakRef = WeakReference(engineRes as EngineResource<*>, resourceReferenceQueue)

            // 3. 把弱引用存起来（模拟 ActiveResources 里缓存它）
            activeResources.put(weakRef.hashCode(), weakRef)
            Log.d("WeakRefDemo", "Created and cached $weakRef")

            activeResources.clear()
            // 4. 模拟资源被回收：清掉强引用，并手动 enqueue
            //    （真实场景是 GC 清掉 referent 后自动 enqueue
            //     这里直接 enqueue 以便 demo 能立即触发）
//            val enqueued = weakRef.enqueue()
//            Log.d("WeakRefDemo", "Manually enqueued? $enqueued")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 优雅关停：中断监控线程，shutdown 线程池
        executor.shutdownNow()
        executor.awaitTermination(1, TimeUnit.SECONDS)
    }

    // 模拟 Glide 在引用队列里收到回收通知后的清理工作
    private fun cleanupActiveReference(ref: WeakReference<EngineResource<*>>) {
        // 从缓存中移除
        val removed = activeResources.remove(ref.hashCode())
        Log.d("WeakRefDemo", "Cleaning up reference $ref, removed from cache? $removed")
        // 这里可以进一步释放对应的资源，比如关闭 bitmaps、取消请求等
    }
}

// 一个简单的资源包装类，模拟 Glide 的 EngineResource<T>
class EngineResource<T>(val data: T) {
    override fun toString(): String = "EngineResource($data)"
}