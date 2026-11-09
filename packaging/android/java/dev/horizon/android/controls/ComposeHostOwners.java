package dev.horizon.android.controls;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

/**
 * Compose needs lifecycle owners on the view tree. The game is a NativeActivity (not a
 * ComponentActivity), so the overlay window's root gets its own.
 */
public final class ComposeHostOwners {
    private ComposeHostOwners() {}

    public static void install(View root, LifecycleOwner lifecycle, SavedStateRegistryOwner savedState, ViewModelStoreOwner store) {
        ViewTreeLifecycleOwner.set(root, lifecycle);
        ViewTreeSavedStateRegistryOwner.set(root, savedState);
        ViewTreeViewModelStoreOwner.set(root, store);
    }
}
