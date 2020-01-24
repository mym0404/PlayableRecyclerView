package happy.mjstudio.playablerecyclerview.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import happy.mjstudio.playablerecyclerview.R
import happy.mjstudio.playablerecyclerview.common.VisibleSize
import happy.mjstudio.playablerecyclerview.enum.PlayableType
import happy.mjstudio.playablerecyclerview.enum.PlayerState
import happy.mjstudio.playablerecyclerview.enum.TargetState
import happy.mjstudio.playablerecyclerview.manager.PlayableManager
import happy.mjstudio.playablerecyclerview.model.Playable
import happy.mjstudio.playablerecyclerview.player.PlayablePlayer
import happy.mjstudio.playablerecyclerview.target.PlayableTarget
import happy.mjstudio.playablerecyclerview.util.attachSnapHelper
import happy.mjstudio.playablerecyclerview.util.debugE
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.math.abs


/**
 * Created by mj on 20, January, 2020
 */
@SuppressLint("Recycle")
class PlayableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    //region Manager

    val manager: PlayableManager = object : PlayableManager {
        override fun pauseAllPlayable() {
            playerPool.forEach { player ->
                if (player.state == PlayerState.PLAYING) {
                    player.pause()
                }
            }
        }

        override fun resumeCurrentPlayable() {
            playNewCandidate()
        }

        override fun updatePlayables() {
            pauseAllPlayable()
            resumeCurrentPlayable()
        }
    }

    //endregion


    //region Variable

    private var playableType: PlayableType = DEFAULT_PLAYABLE_TYPE

    /**
     * max count of players can play it's content concurrently
     *
     * default = 1
     */
    private var videoPlayingConcurrentMax = DEFAULT_VIDEO_PLAYING_CONCURRENT_MAX

    /**
     * Used [PlayablePlayer] count in [playerPool]
     *
     * default = 2
     */
    private var playerPoolCount = DEFAULT_PLAYER_POOL_COUNT

    /**
     * List of [PlayablePlayer] used for playback in List
     */
    private var playerPool = listOf<PlayablePlayer>()
        set(value) {
            updatePlayerPriority()
            field = value
        }

    private val playerQueue =
        PriorityQueue<PlayablePlayer>(playerPoolCount) { lhs, rhs ->
            (lhs.latestUsedTimeMs - rhs.latestUsedTimeMs).toInt()
        }

    private val playerQueueLock = Semaphore(1)

    private var firstCandidatePosition = -1

    /** Device Screen Size */
    private val screenWidth: Int = resources.displayMetrics.widthPixels
    private val screenHeight: Int = resources.displayMetrics.heightPixels

    private var isPageSnapping = false
    //endregion

    init {

        context.obtainStyledAttributes(attrs, R.styleable.PlayableRecyclerView, 0, 0).use {
            playableType = PlayableType.parse(it.getInteger(R.styleable.PlayableRecyclerView_playable_type, DEFAULT_PLAYABLE_TYPE.rawValue))

            playerPoolCount = it.getInteger(R.styleable.PlayableRecyclerView_playable_player_pool_count, DEFAULT_PLAYER_POOL_COUNT)

            videoPlayingConcurrentMax =
                it.getInteger(R.styleable.PlayableRecyclerView_playable_player_concurrent_max, DEFAULT_VIDEO_PLAYING_CONCURRENT_MAX)
        }


        if (isPageSnapping)
            attachSnapHelper()

        observeScrollEvent()
    }

    private fun generatePlayer(): PlayablePlayer = playableType.generatePlayer(context)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode)
            playerPool = (0 until playerPoolCount).map { generatePlayer() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playerPool.forEach {
            it.release()
        }
        playerPool = listOf()
    }

    //region Decide First Candidate

    private fun observeScrollEvent() {
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {

                val newCandidatePosition = computeFirstCandidatePosition()

                if (firstCandidatePosition == newCandidatePosition) return

                firstCandidatePosition = newCandidatePosition

                debugE(TAG, "new candidate : $firstCandidatePosition")

                playNewCandidate()
            }
        })
    }

    /**
     * Returns the visible size of the [Playable] surface on the screen.
     * @param childPosition position for item in LayoutManager
     */
    private fun computeVisibleItemSize(childPosition: Int): VisibleSize? {
        val playableView = (findViewHolderForLayoutPosition(childPosition) as? PlayableTarget)?.getPlayableView() as? View ?: return null

        val playableWidth = playableView.width
        val playableHeight = playableView.height

        val location = IntArray(2)
        playableView.getLocationInWindow(location)

        val startX = location[0]
        val startY = location[1]

        val endX = startX + playableWidth
        val endY = startY + playableHeight

        val clippedWidth = (if (startX < 0) abs(startX) else 0) + (if (endX > screenWidth) endX - screenWidth else 0)
        val clippedHeight = (if (startY < 0) abs(startY) else 0) + (if (endY > screenHeight) endY - screenHeight else 0)

        val visibleWidth = endX - startX - clippedWidth
        val visibleHeight = endY - startY - clippedHeight

        return visibleWidth * visibleHeight
    }

    private fun computeFirstCandidatePosition(): Int {
        val layoutManager = layoutManager as? LinearLayoutManager ?: throw RuntimeException("LayoutManager is not LinearLayoutManager")

        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()

        return (firstVisiblePosition..lastVisiblePosition).maxBy {
            computeVisibleItemSize(it) ?: 0
        } ?: firstVisiblePosition
    }

    //endregion

    //region Play/Pause Video

    private fun getCurrentPlayingVideoCount() = playerPool.count { it.state == PlayerState.PLAYING }

    private fun pauseOldPlayers() {
        if (getCurrentPlayingVideoCount() > videoPlayingConcurrentMax) {
            var pauseCountLatch = getCurrentPlayingVideoCount() - videoPlayingConcurrentMax

            val playersSortedOldest = playerPool.sortedBy { it.latestUsedTimeMs }

            playersSortedOldest.forEach { player ->

                if (pauseCountLatch == 0) return@forEach

                if (player.state == PlayerState.PLAYING) {
                    player.pause()
                    pauseCountLatch -= 1
                }
            }
        }
    }

    private fun playNewCandidate() {

        val adapter = (adapter as? PlayableAdapter<*>) ?: return
        val playable = adapter.currentList.getOrNull(firstCandidatePosition) ?: return

        val target: PlayableTarget = getPlayableTargetWithPosition(firstCandidatePosition) ?: return

        when (target.state) {
            TargetState.ATTACHED -> {
                target.player?.play(playable)
            }
            TargetState.DETACHED -> {
                val newPlayer = dequeueOldestPlayer()
                val oldTarget = newPlayer.target
                newPlayer.detach()

                newPlayer.attach(oldTarget, target)
                newPlayer.play(playable)
            }
        }

        pauseOldPlayers()
    }

    //endregion

    //region Utils

    private fun getPlayableTargetWithView(view: View): PlayableTarget? {
        return getPlayableTargetWithPosition(getChildLayoutPosition(view))
    }

    private fun getPlayableTargetWithPosition(position: Int): PlayableTarget? {
        return findViewHolderForLayoutPosition(position) as? PlayableTarget
    }

    private fun updatePlayerPriority() {
        playerQueueLock.acquire()
        playerQueue.clear()
        playerPool.forEach { playerQueue.offer(it) }
        playerQueueLock.release()
    }

    private fun dequeueOldestPlayer(): PlayablePlayer {
        updatePlayerPriority()
        return playerQueue.peek()?.also { it.updateUsedTime() } ?: throw RuntimeException("WTF")
    }

    //endregion

    companion object {
        private val TAG = PlayableRecyclerView::class.java.simpleName

        private val DEFAULT_PLAYABLE_TYPE = PlayableType.EXOPLAYER
        private const val DEFAULT_PLAYER_POOL_COUNT = 0x02
        private const val DEFAULT_VIDEO_PLAYING_CONCURRENT_MAX = 0x01
    }

}