package com.ryanheise.audioservice;

import com.nt4f04und.android_content_provider.AndroidContentProvider;

import org.jetbrains.annotations.NotNull;

public abstract class ArtContentProvider extends AndroidContentProvider {
    @NotNull
    @Override
    public abstract String getAuthority();

    @NotNull
    @Override
    public abstract String getEntrypointName();
}
