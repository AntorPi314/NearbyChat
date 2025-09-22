package com.antor.nearbychat;

import android.app.Activity;
import android.os.Bundle;

import androidx.core.view.WindowCompat;

public class FriendsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_friends);
    }
}