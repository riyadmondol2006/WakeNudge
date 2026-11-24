package com.riyadm.wakenudge.engine.sleeptrack

import androidx.core.content.FileProvider
import com.riyadm.wakenudge.R

/**
 * Hands the share sheet the one snore clip the user chose to share (copied to a cache folder first). Its own class so it
 * can never clash with another FileProvider in the merged manifest. Not exported; access is granted per share.
 */
class SleepClipProvider : FileProvider(R.xml.sleep_clip_paths)
