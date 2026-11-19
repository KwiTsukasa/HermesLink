package cn.xhs.hermespocket;

final class ChatPresentation {
    private static final int MAX_REVEAL_STEP = 256;

    private ChatPresentation() {}

    static int revealEnd(String target, int previousEnd, boolean completed) {
        int remaining = target.length() - previousEnd;
        if (completed || remaining <= 0) {
            return target.length();
        }
        int step = Math.min(MAX_REVEAL_STEP, Math.max(2, remaining / 10));
        int end = Math.min(target.length(), previousEnd + step);
        if (end < target.length() && Character.isHighSurrogate(target.charAt(end - 1))
                && Character.isLowSurrogate(target.charAt(end))) {
            end++;
        }
        return end;
    }

    static final class Follow {
        private boolean following = true;
        private boolean touching;
        private boolean dragged;
        private boolean seekingLatest;
        private boolean seekingFromHistory;
        private float touchExtremum;
        private int sampledPosition;
        private int sampledTop;
        private int stableSamples;

        boolean followsLatest() {
            return following;
        }

        boolean dragging() {
            return touching || dragged;
        }

        boolean awaitingScrollIdle() {
            return !touching && dragged;
        }

        void touchStarted() {
            touching = true;
            dragged = false;
            seekingLatest = false;
            seekingFromHistory = false;
            stableSamples = 0;
        }

        void touchMoved(float deltaX, float deltaY, int touchSlop, boolean scrollable,
                        boolean atBottom) {
            if (!touching || !scrollable) {
                return;
            }
            if (!dragged) {
                float vertical = Math.abs(deltaY);
                if (vertical <= touchSlop || vertical < Math.abs(deltaX)) {
                    return;
                }
                dragged = true;
                seekingLatest = deltaY < 0;
                touchExtremum = deltaY;
            } else if (seekingLatest) {
                touchExtremum = Math.min(touchExtremum, deltaY);
                if (deltaY - touchExtremum > touchSlop) {
                    seekingLatest = false;
                    seekingFromHistory = false;
                    touchExtremum = deltaY;
                }
            } else {
                touchExtremum = Math.max(touchExtremum, deltaY);
                if (touchExtremum - deltaY > touchSlop) {
                    seekingLatest = true;
                    touchExtremum = deltaY;
                }
            }
            if (seekingLatest) {
                seekingFromHistory |= !atBottom;
            } else {
                following = false;
            }
        }

        void touchEnded(boolean scrolling, boolean atBottom) {
            touching = false;
            if (!scrolling) {
                scrollIdle(atBottom);
            }
        }

        void touchCancelled(boolean scrolling) {
            seekingLatest = false;
            touchEnded(scrolling, false);
        }

        void scrollIdle(boolean atBottom) {
            if (awaitingScrollIdle()) {
                if (seekingLatest && seekingFromHistory && atBottom) {
                    following = true;
                }
                dragged = false;
                seekingLatest = false;
                stableSamples = 0;
            }
        }

        boolean scrollPositionStable(int firstPosition, int firstTop) {
            if (!awaitingScrollIdle()) {
                return false;
            }
            if (stableSamples == 0 || firstPosition != sampledPosition || firstTop != sampledTop) {
                sampledPosition = firstPosition;
                sampledTop = firstTop;
                stableSamples = 1;
            } else {
                stableSamples++;
            }
            return stableSamples >= 3;
        }

        void jumpToLatest() {
            following = true;
            touching = false;
            dragged = false;
            seekingLatest = false;
            seekingFromHistory = false;
            stableSamples = 0;
        }

        void restoreDetached() {
            following = false;
        }
    }
}
