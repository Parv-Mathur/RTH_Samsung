/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2024
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package edu.gatech.ccg.recordthesehands.recording

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.PromptType
import edu.gatech.ccg.recordthesehands.R
import java.io.File

/**
 * This is the little rectangle at the top of the screen that prompts the
 * user with what to sign.
 */
class WordPromptFragment(
  private var prompt: Prompt, @LayoutRes layout: Int,
) : Fragment(layout) {

  companion object {
    private val TAG = VideoPromptController::class.java.simpleName
  }

  var videoPromptController: VideoPromptController? = null

  /**
   * Lay out the UI for this fragment.
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val textView = view.findViewById<TextView>(R.id.promptText)
    if (prompt.prompt != null) {
      Log.d(TAG, "setting prompt to ${prompt.prompt}")
      textView.text = prompt.prompt
      textView.visibility = View.VISIBLE
    } else {
      Log.d(TAG, "no prompt available for ${prompt.key}.")
      textView.visibility = View.GONE
    }

    when (prompt.type) {
      PromptType.TEXT -> {
      }
      PromptType.IMAGE -> {
        Log.d(TAG, "Rendering Image for ${prompt.key}.")
        if (prompt.resourcePath != null) {
          Log.d(TAG, "resourcePath ${prompt.resourcePath}.")
          val imageView = view.findViewById<ImageView>(R.id.promptImage)
          val filepath = File(requireContext().filesDir, prompt.resourcePath)
          imageView.setImageURI(Uri.fromFile(filepath))
          imageView.visibility = View.VISIBLE
        }
      }
      PromptType.VIDEO -> {
        if (prompt.resourcePath != null) {
          val videoView = view.findViewById<VideoView>(R.id.promptVideo)

          // Initialize button for adjusting video size
          val changeScreenSizeButton = view.findViewById<ImageButton>(R.id.changeScreenSizeButton)
          val splitScreenButton = view.findViewById<ImageButton>(R.id.splitScreenButton)

          // Store original video width and height
          val origWidth = videoView.layoutParams.width
          val origHeight = videoView.layoutParams.height

          var fullScreen = false
          var splitScreen = false

          changeScreenSizeButton.setOnClickListener {
            val videoViewParams = videoView.layoutParams
            splitScreen = false
            if (!fullScreen) {
              videoViewParams.width = ViewGroup.LayoutParams.MATCH_PARENT
              videoViewParams.height = ViewGroup.LayoutParams.MATCH_PARENT
              changeScreenSizeButton.setImageResource(R.drawable.minimize_screen_button)
              videoView.layoutParams = videoViewParams
              fullScreen = true
              Log.i(TAG, "Enlarging video to full screen size")
            } else {
              videoViewParams.width = origWidth
              videoViewParams.height = origHeight
              videoView.layoutParams = videoViewParams
              changeScreenSizeButton.setImageResource(R.drawable.full_screen_button)
              fullScreen = false
              Log.i(TAG, "Minimizing video to original size")
            }
          }

          videoPromptController = VideoPromptController(
              requireContext(), null, videoView, prompt.resourcePath!!, true)
          videoView.visibility = View.VISIBLE
          changeScreenSizeButton.visibility = View.VISIBLE
//          splitScreenButton.visibility = View.VISIBLE
        }
      }
    }
  }
}


