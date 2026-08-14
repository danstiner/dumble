#include <gtest/gtest.h>
#include <vector>
#include "core/SlotSet.h"

using dumble::playout::SlotSet;

TEST(SlotSet, ClaimsLowestFreeSlot) {
    SlotSet set;
    EXPECT_EQ(0, set.claim());
    EXPECT_EQ(1, set.claim());
    EXPECT_EQ(2, set.claim());
}

TEST(SlotSet, ReleasedSlotIsReclaimedBeforeHigherOnes) {
    SlotSet set;
    set.claim();
    set.claim();
    set.claim();
    set.release(1);
    EXPECT_EQ(1, set.claim());
}

TEST(SlotSet, FullSetRefuses) {
    SlotSet set;
    for (int i = 0; i < SlotSet::kCapacity; i++) EXPECT_EQ(i, set.claim());
    EXPECT_EQ(-1, set.claim());
    // A refusal must not corrupt occupancy — the engine keeps serving its live speakers.
    EXPECT_EQ(SlotSet::kCapacity, set.size());
    set.release(63);
    EXPECT_EQ(63, set.claim());
}

TEST(SlotSet, TracksOccupancy) {
    SlotSet set;
    EXPECT_TRUE(set.empty());
    EXPECT_EQ(0, set.size());
    const int a = set.claim();
    EXPECT_FALSE(set.empty());
    EXPECT_TRUE(set.occupied(a));
    EXPECT_FALSE(set.occupied(a + 1));
    set.release(a);
    EXPECT_TRUE(set.empty());
    EXPECT_FALSE(set.occupied(a));
}

TEST(SlotSet, ForEachVisitsOccupiedSlotsAscending) {
    SlotSet set;
    for (int i = 0; i < 5; i++) set.claim();
    set.release(1);
    set.release(3);
    std::vector<int> seen;
    set.forEach([&](int i) { seen.push_back(i); });
    EXPECT_EQ(std::vector<int>({0, 2, 4}), seen);
}

TEST(SlotSet, ForEachOnAnEmptySetVisitsNothing) {
    SlotSet set;
    int calls = 0;
    set.forEach([&](int) { calls++; });
    EXPECT_EQ(0, calls);
}

TEST(SlotSet, ReleasingTwiceIsHarmless) {
    // Nothing releases twice today — retirement in fillQuantum is the only caller, and
    // ~PlayoutEngine releases nothing — but claim() hands out the lowest free bit, so a release
    // that flipped a bit rather than clearing it would hand a live speaker's index straight back
    // out to the next session. Cheap to pin, and the failure would be two speakers on one queue.
    SlotSet set;
    const int a = set.claim();
    set.release(a);
    set.release(a);
    EXPECT_TRUE(set.empty());
}
