package com.mhlyak.mmapp.activity;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.widget.TextView;

import com.mhlyak.mmapp.R;
import com.mhlyak.mmapp.fragment.SdlPropertiesFragment;
import com.mhlyak.mmapp.fragment.TestFragment;
import com.mhlyak.mmapp.fragment.VideoStreamingFragment;

public class HomeActivity extends AppCompatActivity {
    public static final String TAG = HomeActivity.class.toString();

    private Fragment activeFragment;
    private FragmentManager fragmentManager;

    private BottomNavigationView bottomNavigationView;

    private TextView mTextMessage;

    private BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            switch (item.getItemId()) {
                case R.id.navigation_home:
                    //mTextMessage.setText(R.string.title_home);
                    mTextMessage.setText("");

                    activeFragment = new SdlPropertiesFragment();
                    break;
                case R.id.navigation_dashboard:
                    //mTextMessage.setText(R.string.title_dashboard);

                    activeFragment = new TestFragment();
                    break;
                case R.id.navigation_notifications:
                    //mTextMessage.setText(R.string.title_notifications);

                    activeFragment = new VideoStreamingFragment();
                    break;
            }

            final FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.replace(R.id.main_container, activeFragment).commit();

            return true;

            // Ce je kaj narobe
            // return false;
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        fragmentManager = getSupportFragmentManager();

        bottomNavigationView = (BottomNavigationView) findViewById(R.id.navigation);
        bottomNavigationView.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener);

        mTextMessage = (TextView) findViewById(R.id.message);

    }

}
