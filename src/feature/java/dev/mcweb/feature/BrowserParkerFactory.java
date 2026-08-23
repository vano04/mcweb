package dev.mcweb.feature;

import com.oracle.svm.core.thread.Parker;

final class BrowserParkerFactory implements Parker.ParkerFactory {
    @Override
    public Parker acquire() {
        return new BrowserParker();
    }
}
