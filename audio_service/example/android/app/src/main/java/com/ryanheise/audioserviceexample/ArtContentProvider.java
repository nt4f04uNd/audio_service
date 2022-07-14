package com.ryanheise.audioserviceexample;

import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;

public class ArtContentProvider extends com.ryanheise.audioservice.ArtContentProvider {
    @NotNull
    @Override
    public String getAuthority() {
        return "com.ryanheise.audioserviceexample.art_content_provider";
    }

    @NotNull
    @Override
    public String getEntrypointName() {
        return "artContentProviderEntryPoint";
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NotNull Uri uri, @NotNull String mode) {
        try {
            Uri actualUri = Uri.parse("content://media" + uri.getPath().replaceFirst(".$",""));
            return getContext().getContentResolver().openFileDescriptor(actualUri, "r");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
}
