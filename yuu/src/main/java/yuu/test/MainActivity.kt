package yuu.test

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
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
        val applicationContext = Glide.with(applicationContext)
        val context = Glide.with(baseContext)
        Log.e(TAG, "onCreate: fragmentActivity hashCode=${fragmentActivity.hashCode()}")
        Log.e(TAG, "onCreate: applicationContext hashCode=${applicationContext.hashCode()}")
        Log.e(TAG, "onCreate: context hashCode=${context.hashCode()}")

        // test
        val sharedOptions: RequestOptions = RequestOptions().placeholder(getDrawable(R.mipmap.fushi)).fitCenter()
        Glide.with(this).load(R.mipmap.fushi).apply(sharedOptions).submit()
        Glide.with(this).load(R.mipmap.fushi).listener(object: RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                Log.e("Glide", "加载失败", e)
                return false // 返回 false，表示 Glide 会继续处理错误
            }

            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                Log.d("Glide", "加载成功")
                return false // 返回 false，表示 Glide 会继续显示资源
            }
        }).into(imageView)
    }
}