#include <gtest/gtest.h>
#include "core/Bitmap.h"

using dumble::playout::Bitmap;

TEST(Bitmap, StartsEmpty) {
    Bitmap set;
    EXPECT_EQ(0, set.count());
    for (int i = 0; i < Bitmap::kCapacity; i++) EXPECT_FALSE(set.test(i));
}

TEST(Bitmap, SetAndClearTrackOneIndexEach) {
    Bitmap set;
    set.set(5);
    EXPECT_TRUE(set.test(5));
    EXPECT_FALSE(set.test(4));
    EXPECT_FALSE(set.test(6));
    EXPECT_EQ(1, set.count());
    set.clear(5);
    EXPECT_FALSE(set.test(5));
    EXPECT_EQ(0, set.count());
}

TEST(Bitmap, TheHighestIndexIsReachable) {
    // The shift is on uint64_t, so the top bit must not be undefined behaviour or a truncation.
    Bitmap set;
    set.set(Bitmap::kCapacity - 1);
    EXPECT_TRUE(set.test(Bitmap::kCapacity - 1));
    EXPECT_EQ(1, set.count());
}

TEST(Bitmap, SettingTwiceIsHarmless) {
    Bitmap set;
    set.set(3);
    set.set(3);
    EXPECT_EQ(1, set.count()) << "set added a bit rather than counting";
}

TEST(Bitmap, ClearingTwiceIsHarmless) {
    // Nothing releases twice today — retirement in fillQuantum is the only caller — but release
    // that flipped a bit rather than clearing it would hand a live speaker's index back out to the
    // next session. Cheap to pin, and the failure would be two speakers on one queue.
    Bitmap set;
    set.set(2);
    set.clear(2);
    set.clear(2);
    EXPECT_FALSE(set.test(2));
    EXPECT_EQ(0, set.count());
}

TEST(Bitmap, CountTalliesEverySetIndex) {
    Bitmap set;
    for (int i = 0; i < Bitmap::kCapacity; i++) set.set(i);
    EXPECT_EQ(Bitmap::kCapacity, set.count());
    set.clear(0);
    set.clear(63);
    EXPECT_EQ(Bitmap::kCapacity - 2, set.count());
}
