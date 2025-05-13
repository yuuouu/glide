package yuu.test

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * 优先级生效的测试类
 */
class PriorityTestActivity : AppCompatActivity() {
    private val TAG = "yuu"

    @SuppressLint("MissingInflatedId", "UseCompatLoadingForDrawables", "CutPasteId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_scroll)
//        findViewById<LinearLayout>(R.id.ly).setOnClickListener {
            loadLocalImageTest()
//        }
    }

    @SuppressLint("VisibleForTests")
    private fun loadLocalImageTest() {
        // 设置线程数量
        val customExecutor = GlideExecutor.newSourceExecutor(2, "test_executor", GlideExecutor.UncaughtThrowableStrategy.DEFAULT)
        Glide.init(this, GlideBuilder().setSourceExecutor(customExecutor))

          val KB489 = "https://isorepublic.com/wp-content/uploads/2024/07/iso-republic-night-full-moon.jpg"
          val KB805 = "https://isorepublic.com/wp-content/uploads/2023/07/iso-republic-lunar-moon-background.jpg"
          val KB871 = "https://isorepublic.com/wp-content/uploads/2020/04/iso-republic-pickle-sandwich-chips-food.jpg"
          val KB872 = "https://isorepublic.com/wp-content/uploads/2018/11/halloween-pumpkin.jpg"
          val KB882 = "https://isorepublic.com/wp-content/uploads/2022/09/iso-republic-chef-food-plate-waitress.jpg"
          val MB6_89 = "https://isorepublic.com/wp-content/uploads/2022/07/iso-republic-fresh-baked-cookies.jpg"

       /* val KB489 = "file:///android_asset/images/489KB.jpg".toUri()
        val KB805 = "file:///android_asset/images/805KB.jpg".toUri()
        val KB871 = "file:///android_asset/images/871KB.jpg".toUri()
        val KB872 = "file:///android_asset/images/872KB.jpg".toUri()
        val KB882 = "file:///android_asset/images/882KB.jpg".toUri()
        val MB6_89 = "file:///android_asset/images/6_89MB.jpg".toUri()*/


        val imageViews = listOf<ImageView>(
            findViewById(R.id.imageView1),
            findViewById(R.id.imageView2),
            findViewById(R.id.imageView3),
            findViewById(R.id.imageView4),
            findViewById(R.id.imageView5),
            findViewById(R.id.imageView6),
        )

        val imageS = listOf(KB489, KB805, KB871, KB872, KB882)
        // Step 1: 发起 5 个低优先级请求，消耗线程资源
        for (i in imageS.indices) {
            val iv = imageViews[i]
            runConcurrentLoad(
                imageUrl = imageS[i],
                priority = Priority.LOW,
                imageView = iv
            )
        }

        // Step 2: 立即发起 1 个高优先级请求
//        imageViews[5].postDelayed({
        runConcurrentLoad(
            imageUrl = MB6_89,
            priority = Priority.IMMEDIATE,
            imageView = imageViews[5]
        )
//        }, 300) // 延迟发起，确保其他任务已占用线程

    }

    private fun runConcurrentLoad(imageUrl: String, priority: Priority, imageView: ImageView) {
        val startTime = System.currentTimeMillis()
        Log.d("PriorityTest", "$TAG PriorityTest $imageUrl start: $startTime")
        val glide = Glide.with(this).load(imageUrl)
            .diskCacheStrategy(DiskCacheStrategy.NONE) // 禁用磁盘缓存
            .priority(priority)

        glide.listener(object : RequestListener<Drawable> {
            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                val endTime = System.currentTimeMillis()
                Log.d("PriorityTest", "$TAG PriorityTest $imageUrl dataSource=$dataSource priority=${glide.priority} duration=${endTime - startTime}ms")
                return false
            }

            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                Log.e("PriorityTest", "$TAG PriorityTest $imageUrl load failed", e)
                return false
            }
        }).into(imageView)
    }
}