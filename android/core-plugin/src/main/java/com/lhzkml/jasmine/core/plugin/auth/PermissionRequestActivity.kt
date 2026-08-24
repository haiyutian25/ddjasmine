package com.lhzkml.jasmine.core.plugin.auth

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import com.lhzkml.jasmine.core.plugin.PluginHost

/**
 * 透明的权限请求 Activity：由 [PluginHost.requestPermission] 启动，代表宿主
 * 向用户弹出系统授权对话框，并把结果回传给挂起中的请求。插件无 UI 也能经
 * 宿主请求运行时权限（权限须在宿主权限池内预声明）。
 */
class PermissionRequestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = intent.getStringExtra(EXTRA_PERMISSION)
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, 0)
        if (permission.isNullOrBlank()) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        PluginHost.completePermissionRequest(requestCode, granted)
        finish()
    }

    companion object {
        const val EXTRA_PERMISSION = "jasmine.plugin.permission"
        const val EXTRA_REQUEST_CODE = "jasmine.plugin.permission.requestCode"
    }
}
