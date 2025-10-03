package com.antor.nearbychat;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

public abstract class BaseActivity extends Activity implements LifecycleOwner {

    private LifecycleRegistry lifecycleRegistry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.markState(androidx.lifecycle.Lifecycle.State.CREATED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.markState(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.markState(androidx.lifecycle.Lifecycle.State.RESUMED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.markState(androidx.lifecycle.Lifecycle.State.DESTROYED);
    }

    @NonNull
    @Override
    public androidx.lifecycle.Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
}