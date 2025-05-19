package yuu.test

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 测试类
 */
class MainActivity: AppCompatActivity() {
    private val TAG = "yuu"

    @SuppressLint("MissingInflatedId", "UseCompatLoadingForDrawables")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // test
        /*   val sharedOptions: RequestOptions = RequestOptions().placeholder(getDrawable(R.mipmap.fushi)).fitCenter()
               .diskCacheStrategy(DiskCacheStrategy.DATA).skipMemoryCache(false).onlyRetrieveFromCache(true).useUnlimitedSourceGeneratorsPool(true).useAnimationPool(true)
           Glide.with(this).load(R.mipmap.fushi).listener(object: RequestListener<Drawable> {
               override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                   Log.e("yuu", "加载失败", e)
                   return false
               }

               override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                   Log.d("yuu", "加载成功")
                   return false
               }
           }).into(imageView)*/

        testCache()
    }

    private fun testCache() {
        val imageView = findViewById<ImageView>(R.id.iv)
        // 获取网络图片
        val KB489 = "https://isorepublic.com/wp-content/uploads/2024/07/iso-republic-night-full-moon.jpg"
        // 点击后再获取图片，方便打断点
        findViewById<ConstraintLayout>(R.id.main).setOnClickListener {
            Glide.with(this).load(KB489).skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.DATA).into(imageView)
        }
        // 清除图片缓存，方便再次查看断点
        imageView.setOnClickListener {
            Glide.with(this).clear(imageView)
            CoroutineScope(Dispatchers.IO).launch {
//                Glide.get(applicationContext).clearDiskCache()
                Glide.get(applicationContext).removeDiskCache(KB489)
            }
        }
    }
}