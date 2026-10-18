package com.gatecontrol.android.ui.familie

import android.content.Context
import com.gatecontrol.android.R

/** A Familie error code as text outside composition (toasts). */
fun familieErrorMessage(ctx: Context, code: String?): String {
    val res = familieErrorRes(code)
    return if (res == R.string.familie_error_generic) ctx.getString(res, code ?: "?") else ctx.getString(res)
}
