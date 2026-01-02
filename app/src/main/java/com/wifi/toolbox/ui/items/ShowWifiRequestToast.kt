package com.wifi.toolbox.ui.items

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast


fun showWifiRequestToast(context: Context) {
    val toast = Toast(context)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(80, 40, 80, 40)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 60f
        }
    }

    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    val titleView = TextView(context).apply {
        layoutParams = lp
        text = "申请网络权限"
        setTextColor(Color.BLACK)
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    val spacer = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, 20)
    }

    val contentView = TextView(context).apply {
        layoutParams = lp
        text = "请在下方弹窗点击允许，\n然后直接切后台返回应用"
        setTextColor(Color.DKGRAY)
        textSize = 16f
        gravity = Gravity.CENTER
    }

    container.addView(titleView)
    container.addView(spacer)
    container.addView(contentView)

    toast.view = container
    toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 300)
    toast.duration = Toast.LENGTH_LONG
    toast.show()
}