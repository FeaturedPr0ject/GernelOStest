package com.coneui.rootpanic;

import android.os.ParcelFileDescriptor;

interface IUpdateUserService {
    void destroy() = 16777114;
    String installApk(in ParcelFileDescriptor apkFd, long size);
}
