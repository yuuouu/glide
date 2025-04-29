package yuu.test

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target

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
        val imageView = findViewById<ImageView>(R.id.iv)
        val fragmentActivity = Glide.with(this)
//        val applicationContextGlide = Glide.with(applicationContext)
//        val context = Glide.with(baseContext)
//        Log.e(TAG, "onCreate: fragmentActivity hashCode=${fragmentActivity.hashCode()}")
//        Log.e(TAG, "onCreate: applicationContext hashCode=${applicationContextGlide.hashCode()}")
//        Log.e(TAG, "onCreate: context hashCode=${context.hashCode()}")

        Glide.with(applicationContext).load(R.mipmap.fushi).into(imageView)
        imageView.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
            finish()
        }

        // test
        val sharedOptions: RequestOptions = RequestOptions().placeholder(getDrawable(R.mipmap.fushi)).fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.DATA).skipMemoryCache(false).onlyRetrieveFromCache(true).useUnlimitedSourceGeneratorsPool(true).useAnimationPool(true)
        Glide.with(this).load(R.mipmap.fushi).preload()
        Glide.with(this).load(R.mipmap.fushi).listener(object: RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                Log.e("yuu", "加载失败", e)
                return false // 返回 false，表示 Glide 会继续处理错误
            }

            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                Log.d("yuu", "加载成功")
                return false // 返回 false，表示 Glide 会继续显示资源
            }
        }).into(imageView)
    }
}