package com.lumora.adapter

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.cache.EpgListCache
import com.lumora.model.Channel
import com.lumora.parser.XtreamClient
import com.lumora.util.PosterLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Placeholder programme lengths while a row's schedule loads, rotated per channel so the
// loading rows don't line up into a grid that reads as real, identical programmes.
private val SKELETON_MINUTES = intArrayOf(45, 30, 60, 30, 45)
private const val NOW_NEXT_SEPARATOR = " · "
// Fast D-pad/fling scrolling flies a row past in well under this time; waiting this
// long before firing its network fetch means rows that never settle never cost a
// request at all - this is what keeps scrolling through thousands of channels smooth.
private const val LOAD_DEBOUNCE_MS = 200L

/**
 * What a programme block currently shows, carried on the block View's own tag.
 *
 * Two jobs: it lets a rebind skip the background work when the answer hasn't changed, and it
 * gives the listeners - attached once when the block is built rather than reassigned on every
 * bind - a way to find the programme the block now represents.
 */
private class BlockState {
    var program: XtreamClient.EpgProgram? = null
    var backgroundRes: Int = 0
}

/** TiviMate-style TV guide: fixed channel column + horizontally-scrolling EPG timeline per row. */
class LiveGuideAdapter(
    private val onChannelClick: (Channel) -> Unit,
    /** The programme is the focused block's, or null when focus is on the channel column. */
    private val onChannelFocused: ((Channel, XtreamClient.EpgProgram?) -> Unit)? = null,
    private val onChannelLongPress: ((Channel) -> Unit)? = null,
    private val onChannelFavClick: ((Channel) -> Unit)? = null,
    private val onProgramLongPress: ((Channel, XtreamClient.EpgProgram) -> Unit)? = null,
    private val isReminderSet: (String) -> Boolean = { false },
    /** False when no reminder is armed at all, so a bind never builds a key per block. */
    private val hasAnyReminder: () -> Boolean = { false },
    private val isChannelFavourite: (String) -> Boolean = { false },
    private val fetchPrograms: suspend (String) -> List<XtreamClient.EpgProgram>?
) : ListAdapter<Channel, LiveGuideAdapter.RowViewHolder>(DiffCallback()) {

    companion object {
        /** Timeline scale (px per programme minute, in dp). Also used by MainActivity's
         *  buildGuideHeader() so the time ruler's 30-minute slots stay in step with the
         *  program blocks below them. */
        const val MINUTE_WIDTH_DP = 2.6f

        /** The half hour [nowMillis] falls in, as unix seconds: where the timeline starts. */
        fun halfHourFloor(nowMillis: Long): Long {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.MILLISECOND, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MINUTE, get(java.util.Calendar.MINUTE) / 30 * 30)
            }
            return cal.timeInMillis / 1000
        }
    }

    /**
     * Where every row's timeline starts, in unix seconds: the half hour the guide was opened
     * in (20:00 for a guide opened at 20:04), set by buildGuideHeader() so the rows and the
     * ruler above them read the same origin. A programme that began earlier is drawn from
     * here. 0 until the ruler has been built; rows then take the current half hour.
     */
    var timelineOriginSeconds: Long = 0

    private val scope = CoroutineScope(Dispatchers.Main)
    private val avatarColors = intArrayOf(
        R.color.channel_1, R.color.channel_2, R.color.channel_3,
        R.color.channel_4, R.color.channel_5, R.color.channel_6
    )

    // Shared horizontal scroll offset across every bound row + the time header, so
    // scrolling one (by D-pad focus or drag) keeps every other row in sync.
    private var sharedScrollX: Int = 0
    private val boundScrollViews = mutableSetOf<HorizontalScrollView>()
    private var headerScrollView: HorizontalScrollView? = null
    private var broadcasting = false

    fun attachHeader(header: HorizontalScrollView) {
        headerScrollView = header
        // The header is itself touch-draggable; without broadcasting its scrolls back
        // out, dragging it desyncs the time ruler from every program row below it.
        header.setOnScrollChangeListener { v, scrollX, _, _, _ -> broadcastScroll(scrollX, v as HorizontalScrollView) }
        if (header.scrollX != sharedScrollX) header.scrollTo(sharedScrollX, 0)
    }

    private fun broadcastScroll(x: Int, source: HorizontalScrollView?) {
        if (sharedScrollX == x) return
        // Re-entrancy guard: rows with less programme content than sharedScrollX clamp
        // their scrollTo below, which fires their own scroll listener mid-loop and would
        // re-broadcast the clamped offset - yanking the row the user is actually
        // scrolling (and every other row) back with it, as a visible jitter fight.
        if (broadcasting) return
        broadcasting = true
        try {
            sharedScrollX = x
            headerScrollView?.takeIf { it !== source && it.scrollX != x }?.scrollTo(x, 0)
            for (sv in boundScrollViews) {
                if (sv !== source && sv.scrollX != x) sv.scrollTo(x, 0)
            }
        } finally {
            broadcasting = false
        }
    }

    /** Rebinds the rows showing [channelId] (its favourite star, its reminder blocks). The
     *  callers used to notifyDataSetChanged(), which rebinds and re-measures every row on
     *  screen for a change to one of them. */
    fun notifyChannelChanged(channelId: String) {
        val list = currentList
        for (i in list.indices) if (list[i].id == channelId) notifyItemChanged(i)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_channel_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: RowViewHolder) {
        super.onViewAttachedToWindow(holder)
        boundScrollViews.add(holder.scrollView)
        if (holder.scrollView.scrollX != sharedScrollX) holder.scrollView.scrollTo(sharedScrollX, 0)
        holder.reloadIfCancelled()
    }

    override fun onViewDetachedFromWindow(holder: RowViewHolder) {
        super.onViewDetachedFromWindow(holder)
        boundScrollViews.remove(holder.scrollView)
        holder.cancelPendingLoad()
    }

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelInfo: LinearLayout = itemView.findViewById(R.id.guideChannelInfo)
        private val numberText: TextView = itemView.findViewById(R.id.guideChannelNumber)
        private val initialText: TextView = itemView.findViewById(R.id.guideChannelInitial)
        private val logoImage: ImageView = itemView.findViewById(R.id.guideChannelLogo)
        private val nameText: TextView = itemView.findViewById(R.id.guideChannelName)
        private val nowText: TextView = itemView.findViewById(R.id.guideChannelNow)
        private val favStar: TextView = itemView.findViewById(R.id.guideChannelFav)
        val scrollView: HorizontalScrollView = itemView.findViewById(R.id.guideProgramScroll)
        private val programRow: LinearLayout = itemView.findViewById(R.id.guideProgramRow)
        private val density = itemView.resources.displayMetrics.density

        /** The avatar circle behind [initialText]. One drawable for the life of the holder,
         *  recoloured per bind - a fresh [GradientDrawable] was being allocated for every row
         *  of every scroll pass when only its colour ever differs. Set as the background once,
         *  in [init]; [GradientDrawable.setColor] invalidates it in place from there on. */
        private val initialBackground = GradientDrawable().apply { shape = GradientDrawable.OVAL }

        private var current: Channel? = null
        private var loadJob: Job? = null
        private var logoJob: Job? = null
        // The EPG last rendered into this row, reused to fill the now/next line under the
        // channel name - never re-fetched. Cleared on every bind so a recycled row can't
        // carry a previous channel's schedule into its now line.
        private var lastPrograms: List<XtreamClient.EpgProgram>? = null
        // The channel whose logo fetch has finished, hit or miss. A miss (dead logo URL)
        // leaves the initial showing, and reloadIfCancelled must not read that as "still
        // loading" and rebind the row every time it scrolls back on screen.
        private var logoSettledFor: Channel? = null

        init {
            initialText.background = initialBackground
            channelInfo.setOnClickListener { current?.let(onChannelClick) }
            channelInfo.setOnLongClickListener { current?.let { onChannelLongPress?.invoke(it) }; true }
            channelInfo.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) current?.let { onChannelFocused?.invoke(it, null) }
                // Focused rows fold the next program into the now line; losing focus
                // (including to a program block) drops back to current-only.
                updateNowLine()
            }
            favStar.setOnClickListener { current?.let { onChannelFavClick?.invoke(it) } }
            // The star is a second D-pad target inside the row: focusing it must behave like
            // focusing the channel column (preview pane follows, now/next line stays) rather
            // than reading as "left the channel".
            favStar.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) current?.let { onChannelFocused?.invoke(it, null) }
                updateNowLine()
            }
            // LEFT from the channel column must land on this row's star, not fall through to
            // the nearest focusable on the whole screen (the sidebar's stars). nextFocusLeftId
            // silently failed to resolve across the guide RecyclerView's focus-search scope,
            // so this is handled by key explicitly, per the repo's D-pad pattern.
            channelInfo.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    favStar.requestFocus()
                    true
                } else false
            }
            favStar.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    channelInfo.requestFocus()
                    true
                } else false
            }
            scrollView.setOnScrollChangeListener { v, scrollX, _, _, _ -> broadcastScroll(scrollX, v as HorizontalScrollView) }
        }

        /** Detaching cancels this row's fetches, and RecyclerView puts a row that scrolled off a
         *  step or two ago back on screen from its view cache without rebinding it. A row whose
         *  schedule or logo hadn't landed by then came back with nothing fetching it, and sat on
         *  the skeleton until the user scrolled it far enough away to be rebound. */
        fun reloadIfCancelled() {
            val channel = current ?: return
            if (loadJob?.isActive == true || logoJob?.isActive == true) return
            val epgMissing = channel.id.isNotBlank() && !EpgListCache.has(channel.id)
            val logoMissing = !channel.logoUrl.isNullOrBlank() && logoImage.visibility != View.VISIBLE &&
                logoSettledFor !== channel
            if (epgMissing || logoMissing) bind(channel)
        }

        fun cancelPendingLoad() {
            loadJob?.cancel()
            loadJob = null
            logoJob?.cancel()
            logoJob = null
        }

        /** Focuses this row's channel column - used to land D-pad focus (and thus the
         *  preview pane) on the first channel as soon as a category is opened, instead of
         *  leaving the guide sitting there unfocused until the user presses something. */
        fun requestChannelFocus() {
            channelInfo.requestFocus()
        }

        fun bind(channel: Channel) {
            current = channel
            // Recycled-row hygiene: a row sliding from an EPG channel to a no-EPG one must
            // not keep the previous channel's now line up through the debounce window, and
            // a bound-but-unfocused row must not show the focused "Now:/Next:" format.
            lastPrograms = null
            nowText.text = ""
            nowText.visibility = View.GONE
            // guideChannelInfo's focus_scale stateListAnimator (scaleX/scaleY/translationZ)
            // can be interrupted mid-transition by fast RecyclerView recycling (this view
            // getting rebound to a different channel before its unfocus animation finishes),
            // leaving a leftover transform on the (reused) View object that bind() would
            // otherwise never touch - the channel name then visually renders shifted/scaled
            // over the program row next to it, even though layout bounds are correct. Forcing
            // a clean baseline on every bind is the standard defense for this.
            channelInfo.scaleX = 1f
            channelInfo.scaleY = 1f
            channelInfo.translationX = 0f
            channelInfo.translationY = 0f
            channelInfo.translationZ = 0f
            val number = channel.tvgChno?.takeIf { it.isNotBlank() }
            numberText.text = number ?: ""
            // GONE, not blank: an empty TextView still measures its own width and pushes
            // the logo right on every row of a catalog that carries no channel numbers.
            numberText.visibility = if (number == null) View.GONE else View.VISIBLE
            nameText.text = channel.name.let { if (it.length > 50) it.take(49) + "…" else it }

            // Star: white when favourited, grey when not. Reads the favourite store through
            // the callback so a notifyDataSetChanged after toggling repaints the whole list.
            val fav = isChannelFavourite(channel.id)
            favStar.text = if (fav) "★" else "☆"
            favStar.setTextColor(
                favStar.context.getColor(if (fav) R.color.text_primary else R.color.text_secondary)
            )
            // Glyph alone gives TalkBack nothing - announce what a press would now do
            // (same per-bind swap as EpisodeAdapter's check button).
            favStar.contentDescription = favStar.context.getString(
                if (fav) R.string.unfavourite else R.string.favourite
            )

            val initial = channel.name.firstOrNull()?.uppercase() ?: "?"
            initialText.text = initial
            // Keyed on the name, not the adapter position: position-keyed colors made the
            // same channel's avatar change color every time a filter shifted the list.
            val colorIndex = (channel.name.hashCode() and 0x7fffffff) % avatarColors.size
            initialBackground.setColor(ContextCompat.getColor(itemView.context, avatarColors[colorIndex]))

            logoImage.setImageDrawable(null)
            val logoUrl = channel.logoUrl
            val cachedLogo = logoUrl?.takeIf { it.isNotBlank() }?.let { PosterLoader.getCached(it) }
            if (cachedLogo != null) {
                logoImage.setImageBitmap(cachedLogo)
                logoImage.visibility = View.VISIBLE
                initialText.visibility = View.GONE
            } else {
                logoImage.visibility = View.GONE
                initialText.visibility = View.VISIBLE
            }

            scrollView.scrollTo(sharedScrollX, 0)
            cancelPendingLoad()

            val epgReady = channel.id.isNotBlank() && EpgListCache.has(channel.id)
            val staleList = if (epgReady || channel.id.isBlank()) null else EpgListCache.get(channel.id)
            if (epgReady) {
                renderPrograms(channel, EpgListCache.get(channel.id))
            } else if (staleList != null) {
                // Gone stale (EpgListCache.has): this channel's own last schedule stays up while
                // it refetches, rather than a row the user is looking at blanking to skeletons.
                renderPrograms(channel, staleList)
            } else {
                // Render the placeholder immediately: this View is recycled and still holds
                // the previous channel's program blocks - leaving them up through the
                // debounce+fetch window pairs the wrong schedule with the new channel
                // while scrolling.
                renderPrograms(channel, null, pending = channel.id.isNotBlank())
            }

            // Debounce both the logo and EPG network fetches: a row that's scrolled
            // past within LOAD_DEBOUNCE_MS never issues a request at all.
            if (cachedLogo == null && !logoUrl.isNullOrBlank()) {
                logoJob = scope.launch {
                    delay(LOAD_DEBOUNCE_MS)
                    val bitmap = PosterLoader.fetch(logoUrl)
                    if (current === channel) {
                        logoSettledFor = channel
                        if (bitmap != null) {
                            logoImage.setImageBitmap(bitmap)
                            logoImage.visibility = View.VISIBLE
                            initialText.visibility = View.GONE
                        }
                    }
                }
            }
            if (!epgReady && channel.id.isNotBlank()) {
                loadJob = scope.launch {
                    delay(LOAD_DEBOUNCE_MS)
                    while (current === channel) {
                        if (EpgListCache.has(channel.id)) {
                            renderPrograms(channel, EpgListCache.get(channel.id))
                            return@launch
                        }
                        if (EpgListCache.markInFlight(channel.id)) {
                            val programs = try {
                                fetchPrograms(channel.id)
                            } catch (e: CancellationException) {
                                // Cancelled mid-fetch (row recycled): release the claim
                                // without caching. Swallowing this (the old runCatching did)
                                // cached null as "no EPG", freezing the channel at
                                // "No programme info" for the rest of the session.
                                EpgListCache.clearInFlight(channel.id)
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            EpgListCache.put(channel.id, programs)
                            if (current === channel) renderPrograms(channel, programs)
                            return@launch
                        }
                        // Another row's fetch for this same id is in flight - poll until it
                        // lands (or claim it ourselves if that fetch gets cancelled) instead
                        // of bailing, which left this row stuck on the placeholder.
                        delay(200)
                    }
                }
            }
        }

        private fun renderPrograms(channel: Channel, programs: List<XtreamClient.EpgProgram>?, pending: Boolean = false) {
            // One clock read for the whole row, not one per block: every block is judging
            // itself against the same instant anyway.
            val nowMillis = System.currentTimeMillis()
            val nowSeconds = nowMillis / 1000
            val origin = timelineOriginSeconds.takeIf { it > 0 } ?: halfHourFloor(nowMillis)
            val count = when {
                pending -> SKELETON_MINUTES.size
                programs.isNullOrEmpty() -> 1
                else -> programs!!.size
            }
            // Only when something is armed: with nothing set (the usual state) the per-block
            // key string is 16 concatenations per bind for a lookup that can only miss.
            val checkReminders = !pending && !programs.isNullOrEmpty() && hasAnyReminder()
            val rotation = if (pending) (channel.id.hashCode() and 0x7fffffff) % SKELETON_MINUTES.size else 0
            // Straight from the programme list into the block Views, with no per-bind list of
            // intermediate block descriptions in between: that list, its 16 objects and the
            // destructuring per block were most of what a row bind allocated (B9).
            // Blocks are laid down at `slot`, which lags `i` by the programmes that ended
            // before the timeline's origin and get no block.
            var slot = 0
            // Where the previous block ended, so a hole in the schedule becomes a gap before
            // the next block instead of pulling it left of where the ruler says it is.
            var cursor = origin
            for (i in 0 until count) {
                val program: XtreamClient.EpgProgram?
                val title: String
                val minutes: Long
                var gapMinutes = 0L
                val isCurrent: Boolean
                if (pending) {
                    // Fetch still out: skeleton blocks rather than "No programme info", which
                    // flashing for 200ms before data lands reads as the guide glitching.
                    program = null
                    title = ""
                    minutes = SKELETON_MINUTES[(i + rotation) % SKELETON_MINUTES.size].toLong()
                    isCurrent = false
                } else if (programs.isNullOrEmpty()) {
                    // No EPG data for this channel - a single filler block keeps the row
                    // clickable; it fills the visible timeline (width 0 + weight, and the
                    // scroller's fillViewport) rather than sitting as one short box among
                    // full rows. Repeating the channel's own name here just reads as
                    // duplicated text right next to the name column, so a neutral label.
                    program = null
                    title = itemView.context.getString(R.string.list_no_programme_info)
                    minutes = 0
                    isCurrent = false
                } else {
                    program = programs!![i]
                    // Ended before the timeline starts: nothing to draw.
                    if (program.stopTimestamp <= origin) continue
                    title = program.title
                    isCurrent = nowSeconds in program.startTimestamp until program.stopTimestamp
                    // A programme that began before the origin is drawn from the origin, so
                    // every row's timeline starts flush at the same half hour as the ruler.
                    val effectiveStart = maxOf(program.startTimestamp, origin)
                    minutes = ((program.stopTimestamp - effectiveStart) / 60).coerceAtLeast(1)
                    // Overlapping entries (start before the previous stop) just butt up.
                    gapMinutes = ((effectiveStart - cursor) / 60).coerceAtLeast(0)
                    cursor = program.stopTimestamp
                }
                val fill = minutes == 0L
                val widthPx = if (fill) 0 else (minutes * MINUTE_WIDTH_DP * density).toInt()
                val gapPx = (gapMinutes * MINUTE_WIDTH_DP * density).toInt()

                // Reuse existing block Views where possible instead of tearing every child
                // down and rebuilding on every rebind - this was the main source of GC
                // churn/jank scrolling through a large guide.
                val block = programRow.getChildAt(slot) as? TextView ?: makeBlock().also { programRow.addView(it) }
                slot++
                // Same leftover-transform defense as channelInfo above - these blocks are
                // reused across binds too, so a stuck transform from a previous focus state
                // would otherwise persist onto whatever program now occupies this block.
                block.scaleX = 1f
                block.scaleY = 1f
                block.translationX = 0f
                block.translationY = 0f
                block.translationZ = 0f
                block.text = title
                val state = block.tag as BlockState
                state.program = program
                val reminderSet = checkReminders && program != null && program.startTimestamp > nowSeconds &&
                    isReminderSet("${channel.id}:${program.startTimestamp}")
                val backgroundRes = when {
                    pending -> R.drawable.bg_guide_block_skeleton
                    reminderSet -> R.drawable.bg_guide_block_reminder
                    isCurrent -> R.drawable.bg_guide_block_current
                    else -> R.drawable.bg_guide_block
                }
                // Only when it actually changed. Assigning a background inflates a Drawable and
                // re-runs the View's padding resolution, and on a rebind the block overwhelmingly
                // ends up with the one it already had.
                if (state.backgroundRes != backgroundRes) {
                    state.backgroundRes = backgroundRes
                    block.setBackgroundResource(backgroundRes)
                }
                // Same idea for the width: setLayoutParams always requests a layout, and
                // rows scrolled back into view mostly get the widths they already have.
                val lp = block.layoutParams as LinearLayout.LayoutParams
                val weight = if (fill) 1f else 0f
                if (lp.width != widthPx || lp.weight != weight || lp.marginStart != gapPx) {
                    lp.width = widthPx
                    lp.weight = weight
                    lp.marginStart = gapPx
                    block.layoutParams = lp
                }
            }
            while (programRow.childCount > slot) {
                programRow.removeViewAt(programRow.childCount - 1)
            }
            // Store and render the now/next line from the same data that built the blocks,
            // so it tracks the timeline highlight. Runs on both the synchronous bind path
            // and the async loadJob path (callers guard with `current === channel`), which
            // is exactly what keeps a recycled row's line from going stale.
            lastPrograms = programs
            updateNowLine()
            // The band above the guide describes the focused row. A schedule that lands after
            // focus did has to reach it too, or the band stays blank for a channel it now knows.
            if (channelInfo.isFocused || favStar.isFocused) onChannelFocused?.invoke(channel, null)
        }

        /** Fills guideChannelNow from the row's already-fetched EPG. Unfocused rows show
         *  just the current program title; the focused row appends the next one
         *  ("Now: X · Next: Y") so a D-pad user browsing vertically sees what's on now and
         *  what's coming. No EPG (or no program spanning now) leaves the line empty/gone. */
        private fun updateNowLine() {
            val programs = lastPrograms
            if (programs.isNullOrEmpty()) {
                nowText.text = ""
                nowText.visibility = View.GONE
                return
            }
            val nowSeconds = System.currentTimeMillis() / 1000
            // Mirror renderPrograms' isCurrent predicate exactly (now in [start, stop)), so
            // the highlighted timeline block and this line always name the same program.
            val currentIndex = programs.indexOfFirst { nowSeconds in it.startTimestamp until it.stopTimestamp }
            val current = if (currentIndex >= 0) programs[currentIndex] else null
            // Next = the program right after the current one; if nothing airs right now
            // (EPG gap), the first upcoming program.
            val next = if (currentIndex >= 0) programs.getOrNull(currentIndex + 1)
            else programs.firstOrNull { it.startTimestamp > nowSeconds }
            // The fav star is a second focus target in the channel column (see its
            // OnFocusChangeListener) - focusing it must expand the now/next line exactly
            // like focusing the channel column does, not read as "left the channel".
            val focused = channelInfo.isFocused || favStar.isFocused
            val context = itemView.context
            nowText.text = when {
                focused && current != null && next != null ->
                    context.getString(R.string.list_now_program, current.title) + NOW_NEXT_SEPARATOR +
                        context.getString(R.string.list_next_program, next.title)
                focused && current != null -> context.getString(R.string.list_now_program, current.title)
                focused && next != null -> context.getString(R.string.list_next_program, next.title)
                current != null -> current.title
                else -> ""
            }
            nowText.visibility = if (nowText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        private fun makeBlock(): TextView {
            val context = itemView.context
            return TextView(context).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                // From resources rather than a literal so the TV override in
                // values-television/dimens.xml applies here too - these blocks are built in
                // code, so they'd otherwise stay phone-sized while the rest of the guide row
                // (inflated from XML) grew around them.
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    context.resources.getDimension(R.dimen.guide_program_text)
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (2 * density).toInt()
                }
                // Listeners are attached here, once per block View, instead of being replaced
                // on every bind - three lambda allocations per block per bind, across every
                // block of every row, was pure churn. They read the row's current channel and
                // the block's current programme at fire time, which is the same thing the
                // captured values used to be.
                tag = BlockState()
                setOnClickListener { current?.let(onChannelClick) }
                // D-pad RIGHT off the channel column moves focus into these program blocks -
                // without this, the preview pane only ever updated while focus sat on the
                // channel name itself and otherwise went stale/blank while browsing the guide.
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) current?.let { onChannelFocused?.invoke(it, (view.tag as BlockState).program) }
                }
                setOnLongClickListener { view ->
                    val channel = current
                    val program = (view.tag as BlockState).program
                    // Clock read at press time rather than carried over from the bind: a guide
                    // row can sit on screen for a long while, and a programme that had not
                    // started when the block was bound may well have started by now.
                    if (channel != null && program != null &&
                        program.startTimestamp > System.currentTimeMillis() / 1000
                    ) {
                        onProgramLongPress?.invoke(channel, program)
                    }
                    true
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
    }
}
