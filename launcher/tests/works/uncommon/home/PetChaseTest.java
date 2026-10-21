package works.uncommon.home;

/** Pure Java checks for continuous boarding, riding, chasing and tree landings. */
public final class PetChaseTest {
    public static void main(String[] args) {
        for (int width : new int[]{320, 480, 640, 960}) {
            PetChase p = new PetChase();
            int restSamples = 0;
            float previousDog = 0, previousCat = 0, previousLift = PetChase.TREE_HEIGHT;
            boolean sawRide = false, sawCatChase = false, sawDogChase = false;
            for (long time = 0; time < PetChase.CYCLE_MS * 2; time += 10) {
                p.at(time, width);
                check(p.ridingStory == (time < PetChase.CYCLE_MS), "Alternate ride and chase stories");
                check(p.catLift >= 0, "Cat never below the floor");
                if (p.phase == PetChase.REST) {
                    restSamples++;
                    check(Math.abs(p.dogX - width * 0.68f) < 0.01, "Dog in own bed");
                }
                if (p.catSleeping()) {
                    check(Math.abs(p.catX - width * 0.87f) < 0.01, "Tofu sleeps on his tree");
                    check(p.catLift == PetChase.TREE_HEIGHT, "Sleeping cat is on the top perch");
                }
                if (p.passenger()) {
                    sawRide = true;
                    check(Math.abs(p.catX - p.dogX) <= PetChase.RIDER_OFFSET + 0.01, "Passenger stays on Moose");
                    check(p.catLift == PetChase.BACK_HEIGHT, "Passenger sits at back height");
                    check(p.catRight == p.dogRight, "Passenger faces with Moose");
                }
                if (p.phase == PetChase.CAT_CHASES || p.phase == PetChase.DOG_CHASES) {
                    check(p.catX > p.dogX, "Cat behind when going left, ahead going right");
                    check(p.dogRight == (p.phase == PetChase.DOG_CHASES), "Pursuit direction");
                    check(p.catLift == 0, "Chase stays on the ground");
                    sawCatChase |= p.phase == PetChase.CAT_CHASES;
                    sawDogChase |= p.phase == PetChase.DOG_CHASES;
                }
                if (time > 0) {
                    check(Math.abs(p.dogX - previousDog) < 3, "Dog position continuity");
                    check(Math.abs(p.catX - previousCat) < 3, "Cat position continuity");
                    check(Math.abs(p.catLift - previousLift) < 1, "No teleport between tree, back and ground");
                    long t = time % PetChase.CYCLE_MS;
                    if (!p.ridingStory && (t == 16000 || t == 24000)) {
                        check(p.dogX < -32 || p.dogX > width + 32, "Dog turns offscreen");
                        check(p.catX < -24 || p.catX > width + 24, "Cat turns offscreen");
                    }
                }
                previousDog = p.dogX; previousCat = p.catX; previousLift = p.catLift;
            }
            check(sawRide && sawCatChase && sawDogChase, "All three play behaviours occur");
            check(restSamples == 18000, "90 seconds resting in each two-minute cycle");
            for (long cycle : new long[]{0, PetChase.CYCLE_MS}) {
                p.at(cycle + 29000, width);
                check(p.catLift == (cycle == 0 ? PetChase.BACK_HEIGHT : 0),
                        "Jump to tree starts on Moose or ground");
                p.at(cycle + 30000, width);
                check(p.catLift > PetChase.TREE_HEIGHT / 2, "Jump has visible height");
                p.at(cycle + 31000, width);
                check(p.catLift == PetChase.TREE_HEIGHT, "Jump lands on perch");
            }
            // A new screen evaluates the same timestamp without resetting the story.
            PetChase anotherScreen = new PetChase();
            p.at(22000, width); anotherScreen.at(22000, width);
            check(p.catX == anotherScreen.catX && p.catLift == anotherScreen.catLift,
                    "Home and All apps share the same position");
            p.at(-1, width);
            check(p.phase == PetChase.REST, "Negative elapsed time is safe");
        }
        System.out.println("Pets: ride, both chases, jump continuity, tree landings and sleep passed at four widths.");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
