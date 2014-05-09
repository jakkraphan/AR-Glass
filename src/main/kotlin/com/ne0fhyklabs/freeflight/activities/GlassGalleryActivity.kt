package com.ne0fhyklabs.freeflight.activities

import android.support.v4.app.FragmentActivity
import android.os.Bundle
import com.ne0fhyklabs.freeflight.R
import com.ne0fhyklabs.freeflight.tasks.GetMediaObjectsListTask.MediaFilter
import com.ne0fhyklabs.freeflight.tasks.GetMediaObjectsListTask
import android.os.AsyncTask.Status
import com.ne0fhyklabs.freeflight.vo.MediaVO
import android.util.Log
import java.util.concurrent.ExecutionException
import java.util.ArrayList
import com.google.android.glass.widget.CardScrollView
import com.google.android.glass.widget.CardScrollAdapter
import android.view.View
import android.view.ViewGroup
import android.content.Context
import kotlin.properties.Delegates
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.ne0fhyklabs.freeflight.utils.ARDroneMediaGallery
import com.ne0fhyklabs.freeflight.utils.ShareUtils
import android.widget.ImageView
import android.view.LayoutInflater
import com.ne0fhyklabs.freeflight.tasks.LoadMediaThumbTask
import android.widget.ImageView.ScaleType
import com.google.android.glass.media.Sounds
import android.media.AudioManager
import android.content.Intent
import com.google.glass.widget.MessageDialog

/**
 * Used to display the photos, and videos taken by the AR Drone on glass.
 */
public class GlassGalleryActivity : FragmentActivity() {

    object Static {
        val TAG = javaClass<GlassGalleryActivity>().getName()
    }

    object IntentExtras {
        val SELECTED_ELEMENT = "SELECTED_ELEMENT"
        val MEDIA_FILTER = "MEDIA_FILTER"
    }

    private var mInitMediaTask: GetMediaObjectsListTask? = null
    private var mMediaFilter: MediaFilter? = null

    private val mMediaGallery: ARDroneMediaGallery by Delegates.lazy {
        ARDroneMediaGallery(getApplicationContext())
    }

    private val mAdapter: GlassGalleryAdapter by Delegates.lazy {
        val mediaList = ArrayList<MediaVO>()
        val galleryAdapter = GlassGalleryAdapter(mediaList, getApplicationContext()!!)
        galleryAdapter
    }

    private val mCardsScroller: CardScrollView by Delegates.lazy {
        val cardsScroller = findViewById(R.id.glass_gallery) as CardScrollView
        cardsScroller.setAdapter(mAdapter)
        cardsScroller.setHorizontalScrollBarEnabled(true)
        cardsScroller.setOnItemClickListener { parent, view, position, id -> openOptionsMenu() }
        cardsScroller
    }

    private val mNoMediaView: TextView by Delegates.lazy {
        findViewById(R.id.glass_gallery_no_media) as TextView
    }

    private var mDisableExitSound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow()?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_glass_gallery)

        val intent = getIntent()
        val selectedItem = if (savedInstanceState != null)
            savedInstanceState.getInt(IntentExtras.SELECTED_ELEMENT, 0)
        else intent?.getIntExtra(IntentExtras.SELECTED_ELEMENT, 0) ?: 0

        val mediaFilterType = intent?.getIntExtra(IntentExtras.MEDIA_FILTER,
                MediaFilter.IMAGES.ordinal()) as Int
        mMediaFilter = MediaFilter.values()[mediaFilterType]

        initMediaTask(mMediaFilter!!, selectedItem)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (mInitMediaTask != null && mInitMediaTask!!.getStatus() == Status.RUNNING) {
            mInitMediaTask?.cancel(false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!mDisableExitSound) {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.playSoundEffect(Sounds.DISMISSED);
        }

        //Restore the exit sound if it's been disabled
        mDisableExitSound = false
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        getMenuInflater().inflate(R.menu.menu_glass_gallery, menu)

        if (mMediaFilter == MediaFilter.IMAGES) {
            //Hide the play menu button
            val playMenu = menu?.findItem(R.id.menu_video_play)
            playMenu?.setEnabled(false)
            playMenu?.setVisible(false)
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        //Get the currently viewed media item
        val selectedPosition = mCardsScroller.getSelectedItemPosition()
        val selectedMedia = mAdapter.getItem(selectedPosition) as MediaVO

        when(item?.getItemId()) {
            R.id.menu_video_play -> {
                playVideo(selectedMedia)
                return true
            }

        //GDK doesn't yet support share intent.
        /*R.id.menu_media_share -> {
            shareMedia(selectedMedia)
            return true
        }*/

            R.id.menu_media_delete -> {
                confirmDeleteMedia(selectedMedia)
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun playVideo(video: MediaVO) {
        if (!video.isVideo()) {
            Log.e(Static.TAG, "Error: trying to play image as video")
            return
        }

        mDisableExitSound = true
        startActivity(Intent(this, javaClass<GlassVideoPlayerActivity>()).putExtra
        (GlassVideoPlayerActivity.Static.EXTRA_VIDEO_URI, video.getUri().toString()))
    }

    private fun shareMedia(media: MediaVO) {
        if (media.isVideo()) {
            ShareUtils.shareVideo(this, media.getPath())
        } else {
            ShareUtils.sharePhoto(this, media.getPath())
        }
    }

    private fun confirmDeleteMedia(media: MediaVO) {
        MessageDialog.Builder(this).setTemporaryIcon(R.drawable.ic_delete_50)
        ?.setTemporaryMessage("Deleting")
        ?.setIcon(R.drawable.ic_done_50)
        ?.setMessage("Deleted")
        ?.setDismissable(true)
        ?.setAutoHide(true)
        ?.setListener(object : MessageDialog.SimpleListener() {
            override fun onDone() = deleteMedia(media)
        })
        ?.build()?.show()
    }

    private fun deleteMedia(media: MediaVO){
        val mediaId = IntArray(1)
        mediaId.set(0, media.getId())

        if (media.isVideo()) {
            mMediaGallery.deleteVideos(mediaId)
        } else {
            mMediaGallery.deleteImages(mediaId)
        }

        mAdapter.remove(media)
        updateView(null)
    }

    private fun initMediaTask(filter: MediaFilter, selectedElem: Int) {
        if (mInitMediaTask == null || mInitMediaTask!!.getStatus() != Status.RUNNING) {
            mInitMediaTask = object : GetMediaObjectsListTask(this, filter) {
                override fun onPostExecute(result: List<MediaVO>) = onMediaScanCompleted(selectedElem, result)
            }

            try {
                mInitMediaTask!!.execute().get()
            } catch(e: InterruptedException) {
                Log.e(Static.TAG, e.getMessage(), e)
            } catch(e: ExecutionException) {
                Log.e(Static.TAG, e.getMessage(), e)
            }
        }
    }

    private fun onMediaScanCompleted(selectedElem: Int, result: List<MediaVO>) {
        initView(selectedElem, result)
        mInitMediaTask = null
    }

    private fun initView(selectedElem: Int, result: List<MediaVO>) {
        mAdapter.clear()
        mAdapter.addAll(result)

        updateView(selectedElem)
    }

    private fun updateView(selectedElem: Int?) {
        if (mAdapter.getCount() > 0) {
            mNoMediaView.setVisibility(View.GONE)

            mCardsScroller.setVisibility(View.VISIBLE)
            if (selectedElem != null)
                mCardsScroller.setSelection(selectedElem)

            if (!mCardsScroller.isActivated())
                mCardsScroller.activate()
        } else {
            mNoMediaView.setVisibility(View.VISIBLE)

            mCardsScroller.deactivate()
            mCardsScroller.setVisibility(View.GONE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val cardsScroller = findViewById(R.id.glass_gallery) as CardScrollView
        outState.putInt(IntentExtras.SELECTED_ELEMENT, cardsScroller.getSelectedItemPosition())
    }

    private class GlassGalleryAdapter(private val mediaList: ArrayList<MediaVO>,
                                      private val context: Context) : CardScrollAdapter() {

        val mInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        fun clear() {
            mediaList.clear()
            notifyDataSetChanged()
        }

        fun addAll(items: List<MediaVO>) {
            mediaList.addAll(items)
            notifyDataSetChanged()
        }

        fun remove(media: MediaVO) {
            mediaList.remove(media)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = mediaList.size()

        override fun getItem(position: Int): Any? = mediaList.get(position)

        override fun getView(position: Int, view: View?, viewGroup: ViewGroup): View? {
            val mediaDetail = getItem(position) as MediaVO

            val mediaCard = mInflater.inflate(R.layout.glass_gallery_item, viewGroup, false)
            val imageView = mediaCard?.findViewById(R.id.image) as ImageView
            val imageIndicatorView = mediaCard?.findViewById(R.id.btn_play)

            if (!mediaDetail.isVideo()) {
                imageIndicatorView?.setVisibility(View.INVISIBLE)
                imageView.setScaleType(ScaleType.CENTER_CROP)
            }

            LoadMediaThumbTask(mediaDetail, imageView).execute()
            return mediaCard
        }

        override fun getPosition(item: Any?): Int {
            if (item !is MediaVO)
                return -1

            val mediaVO = item as MediaVO
            for (i in 0..(getCount() - 1)) {
                if (mediaVO.equals(getItem(i)))
                    return i
            }

            return -1
        }

    }
}