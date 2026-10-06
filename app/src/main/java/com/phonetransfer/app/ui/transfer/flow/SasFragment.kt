package com.phonetransfer.app.ui.transfer.flow

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.p2p.P2pTransfer

/** 第 4 步：SAS 短认证串人工比对（协议 §10，防中间人）。 */
class SasFragment : Fragment(R.layout.fragment_flow_sas) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMode(view)
        view.findViewById<TextView>(R.id.sas_digits).text =
            TransferFlowSession.sas.toString().padStart(4, '0').chunked(2).joinToString(" ")

        view.findViewById<View>(R.id.sas_ok).setOnClickListener {
            if (TransferFlowSession.realSession) P2pTransfer.confirmSas(true) else TransferFlowSession.confirmSas()
            openFlowStep(ItemSelectFragment())
        }
        view.findViewById<View>(R.id.sas_reject).setOnClickListener {
            if (TransferFlowSession.realSession) {
                P2pTransfer.confirmSas(false)
            } else {
                TransferFlowSession.abortSas()
            }
            openFlowStep(ReportFragment())
        }
    }
}
