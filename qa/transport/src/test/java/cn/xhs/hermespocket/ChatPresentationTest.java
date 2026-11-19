package cn.xhs.hermespocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatPresentationTest {
    @Test
    public void revealsActiveTextInStepsWithoutSplittingSupplementaryCharacters() {
        String reply = "看🙂逐字显示🚀途中不会出现半个字符";
        int end = ChatPresentation.revealEnd(reply, 0, false);
        assertTrue("The first live frame must show a partial reply", end > 0 && end < reply.length());

        while (end < reply.length()) {
            assertFalse(Character.isHighSurrogate(reply.charAt(end - 1)));
            int next = ChatPresentation.revealEnd(reply, end, false);
            assertTrue("Every animation step must reveal more text", next > end && next <= reply.length());
            end = next;
        }
        assertEquals(reply.length(), ChatPresentation.revealEnd(reply, end, false));
    }

    @Test
    public void reconcilesCompletedAndShorterReplies() {
        String completed = "包含改写的完整回复🙂";
        assertEquals(completed.length(), ChatPresentation.revealEnd(completed, 2, true));
        assertEquals(2, ChatPresentation.revealEnd("🙂", 15, false));
        assertEquals(0, ChatPresentation.revealEnd("", 15, false));
    }

    @Test
    public void revealsALargeLiveBacklogGraduallyAndCatchesUpWhenComplete() {
        String reply = "甲".repeat(255) + "🙂" + "乙".repeat(4096);
        int end = ChatPresentation.revealEnd(reply, 0, false);
        assertTrue("A large live frame reveals up to 256 UTF-16 units plus a surrogate adjustment",
                end > 2 && end <= 257);
        assertFalse(Character.isHighSurrogate(reply.charAt(end - 1)));
        assertEquals(reply.length(), ChatPresentation.revealEnd(reply, end, true));
    }

    @Test
    public void pausesFollowOnADragUntilTheUserReturnsToTheBottom() {
        ChatPresentation.Follow follow = new ChatPresentation.Follow();
        assertTrue(follow.followsLatest());
        follow.touchStarted();
        assertTrue(follow.dragging());
        assertTrue(follow.followsLatest());
        follow.touchEnded(false, false);
        assertFalse(follow.dragging());
        assertTrue("A tap must leave automatic following enabled", follow.followsLatest());

        follow.touchStarted();
        follow.touchMoved(0, 40, 8, true, true);
        assertFalse(follow.followsLatest());
        follow.touchEnded(true, false);
        assertTrue("The gesture remains active during a fling", follow.dragging());
        follow.scrollIdle(false);
        assertFalse(follow.dragging());
        assertFalse(follow.followsLatest());

        follow.scrollIdle(true);
        assertFalse("Programmatic layout at the bottom must preserve the user's scroll position",
                follow.followsLatest());

        follow.touchStarted();
        follow.touchMoved(0, -40, 8, true, false);
        follow.touchEnded(false, false);
        assertFalse("A gesture toward the latest message must reach the bottom", follow.followsLatest());

        follow.touchStarted();
        follow.touchMoved(0, -40, 8, true, false);
        follow.touchEnded(false, true);
        assertFalse(follow.dragging());
        assertTrue("Manually reaching the bottom resumes following", follow.followsLatest());
    }

    @Test
    public void aHistoryDirectedGestureIgnoresStaleBottomSignals() {
        ChatPresentation.Follow follow = new ChatPresentation.Follow();
        follow.touchStarted();
        follow.touchMoved(0, 40, 8, true, true);
        assertFalse(follow.followsLatest());

        follow.scrollIdle(true);
        assertFalse(follow.followsLatest());
        follow.touchEnded(true, false);
        assertTrue(follow.awaitingScrollIdle());
        follow.scrollIdle(true);
        assertFalse(follow.dragging());
        assertFalse("An idle event from the old bottom layout cannot restore following",
                follow.followsLatest());

        follow.touchStarted();
        follow.touchEnded(false, true);
        assertFalse(follow.followsLatest());

        follow.touchStarted();
        follow.touchMoved(0, -40, 8, true, true);
        follow.touchEnded(false, true);
        assertFalse("A gesture staying at the bottom must leave detached mode unchanged",
                follow.followsLatest());
        follow.jumpToLatest();
        assertTrue(follow.followsLatest());
    }

    @Test
    public void deliberateReversalTowardLatestCanResumeFollowWithinAGesture() {
        ChatPresentation.Follow follow = new ChatPresentation.Follow();
        follow.touchStarted();
        follow.touchMoved(0, 40, 8, true, true);
        follow.touchMoved(0, 36, 8, true, false);
        follow.touchEnded(false, true);
        assertFalse("A small reversal cannot restore following", follow.followsLatest());

        follow.jumpToLatest();
        follow.touchStarted();
        follow.touchMoved(0, 40, 8, true, true);
        follow.touchMoved(0, 20, 8, true, false);
        follow.scrollIdle(true);
        assertFalse("A stale idle notification while the finger is down cannot restore following",
                follow.followsLatest());
        follow.touchEnded(false, true);
        assertTrue("A deliberate reversal can restore following at the bottom", follow.followsLatest());
    }

    @Test
    public void keepsFollowingAtBottomThroughNonscrollingAndLatestDirectedGestures() {
        ChatPresentation.Follow follow = new ChatPresentation.Follow();
        follow.touchStarted();
        follow.touchMoved(0, 40, 8, false, true);
        follow.touchEnded(false, true);
        assertTrue("Content that fits the screen cannot be scrolled toward history",
                follow.followsLatest());

        follow.touchStarted();
        follow.touchMoved(40, 14, 8, true, true);
        follow.touchEnded(false, true);
        assertTrue("A horizontal gesture leaves the scroll preference unchanged",
                follow.followsLatest());

        follow.touchStarted();
        follow.touchMoved(0, -40, 8, true, true);
        assertTrue("Movement toward the latest message preserves follow when already active",
                follow.followsLatest());
        follow.touchEnded(false, true);
        assertTrue(follow.followsLatest());
    }

    @Test
    public void settlesAMissedScrollIdleOnlyAfterTheListStopsMoving() {
        ChatPresentation.Follow follow = new ChatPresentation.Follow();
        follow.touchStarted();
        follow.touchMoved(0, 40, 8, true, true);
        assertFalse(follow.awaitingScrollIdle());
        follow.touchEnded(true, false);
        assertTrue(follow.awaitingScrollIdle());

        assertFalse(follow.scrollPositionStable(12, -32));
        assertFalse(follow.scrollPositionStable(12, -32));
        assertFalse(follow.scrollPositionStable(12, -24));
        assertFalse(follow.scrollPositionStable(12, -24));
        assertFalse(follow.scrollPositionStable(11, -24));
        assertFalse(follow.scrollPositionStable(11, -24));
        assertTrue(follow.scrollPositionStable(11, -24));

        follow.scrollIdle(false);
        assertFalse(follow.awaitingScrollIdle());
        assertFalse(follow.dragging());
        assertFalse("A fling that ends above the newest message keeps following paused",
                follow.followsLatest());
    }

    @Test
    public void remembersDetachedModeUntilAnExplicitJumpToLatest() {
        ChatPresentation.Follow follow = new ChatPresentation.Follow();
        follow.restoreDetached();
        assertFalse(follow.followsLatest());
        follow.touchStarted();
        follow.touchEnded(false, true);
        assertFalse("Tapping at the newest message must preserve detached mode", follow.followsLatest());

        follow.jumpToLatest();
        assertFalse(follow.dragging());
        assertTrue(follow.followsLatest());
    }
}
