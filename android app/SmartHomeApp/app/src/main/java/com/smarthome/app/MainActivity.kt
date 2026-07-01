package com.smarthome.app

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.smarthome.app.databinding.ActivityMainBinding
import com.smarthome.app.fragment.ControlFragment
import com.smarthome.app.fragment.DashboardFragment
import com.smarthome.app.fragment.NotificationFragment
import com.smarthome.app.fragment.RuleFragment
import com.smarthome.app.fragment.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { loadFragment(DashboardFragment()); true }
                R.id.nav_control -> { loadFragment(ControlFragment()); true }
                R.id.nav_rules -> { loadFragment(RuleFragment()); true }
                R.id.nav_notify -> { loadFragment(NotificationFragment()); true }
                R.id.nav_settings -> { loadFragment(SettingsFragment()); true }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}