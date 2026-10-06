package com.phonetransfer.app.ui.mine

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R

/** 通用文本页：迁移报告（当前为空态）、权限与隐私、关于。 */
class TextPageFragment : Fragment(R.layout.fragment_text_page) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        view.findViewById<TextView>(R.id.page_title)
            .setText(args.getInt(ARG_TITLE_RES))
        view.findViewById<TextView>(R.id.page_body).text =
            args.getString(ARG_BODY_TEXT).orEmpty()
    }

    companion object {
        private const val ARG_TITLE_RES = "arg_title_res"
        private const val ARG_BODY_TEXT = "arg_body_text"

        fun newInstance(@StringRes titleRes: Int, body: String): TextPageFragment =
            TextPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE_RES, titleRes)
                    putString(ARG_BODY_TEXT, body)
                }
            }
    }
}
