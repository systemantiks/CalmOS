package works.uncommon.home;

/** Two alternating stories on one shared clock: a hidden passenger, then zoomies. */
final class PetChase {
    static final long CYCLE_MS = 120000;
    static final int REST = 0, WAKE = 1, LEAVE_TREE = 2, CAT_CHASES = 3,
            DOG_CHASES = 4, WALK_LEFT = 5, LOOK_LEFT = 6, WALK_RIGHT = 7,
            LOOK_RIGHT = 8, RETURN = 9, TREE_JUMP = 10, SETTLE = 11, DOG_HOME = 12;
    static final float TREE_HEIGHT = 52, BACK_HEIGHT = 26, RIDER_OFFSET = 4;
    int phase;
    float dogX, catX, catLift, progress;
    boolean dogRight, catRight, ridingStory;

    static float bedX(int width) { return width * 0.68f; }
    static float treeX(int width) { return width * 0.87f; }
    static float treeApproach(int width) { return treeX(width) - 48; }

    void at(long elapsed, int width) {
        elapsed = Math.max(0, elapsed);
        long t = elapsed % CYCLE_MS;
        ridingStory = (elapsed / CYCLE_MS) % 2 == 0;
        float bed = bedX(width), tree = treeX(width), approach = treeApproach(width);
        dogX = bed; catX = tree; catLift = TREE_HEIGHT;
        dogRight = true; catRight = true; progress = 0;
        if (t < 6000 || t >= 36000) {
            phase = REST;
        } else if (t < 7500) {
            phase = WAKE;
            dogRight = false;
            progress = (t - 6000) / 1500f;
        } else if (t < 9000) {
            phase = LEAVE_TREE;
            progress = (t - 7500) / 1500f;
            dogRight = catRight = false;
            catX = lerp(tree, ridingStory ? bed - RIDER_OFFSET : tree, progress);
            catLift = arc(TREE_HEIGHT, ridingStory ? BACK_HEIGHT : 0, 20, progress);
        } else if (t < 29000) {
            if (ridingStory) {
                float left = width * 0.52f;
                if (t < 17000) {
                    phase = WALK_LEFT; dogRight = false;
                    progress = (t - 9000) / 8000f;
                    dogX = lerp(bed, left, progress);
                } else if (t < 19000) {
                    phase = LOOK_LEFT; dogRight = false; dogX = left;
                    progress = (t - 17000) / 2000f;
                } else if (t < 27000) {
                    phase = WALK_RIGHT;
                    progress = (t - 19000) / 8000f;
                    dogX = lerp(left, approach, progress);
                } else {
                    phase = LOOK_RIGHT; dogX = approach;
                    progress = (t - 27000) / 2000f;
                }
                catRight = dogRight;
                // Turn the passenger around the centre of the back at the pause.
                float offset = dogRight ? RIDER_OFFSET : -RIDER_OFFSET;
                if (phase == LOOK_LEFT) offset = lerp(-RIDER_OFFSET, RIDER_OFFSET, progress);
                catX = dogX + offset;
                catLift = BACK_HEIGHT;
            } else if (t < 16000) {
                phase = CAT_CHASES; dogRight = catRight = false;
                progress = (t - 9000) / 7000f;
                dogX = lerp(bed, -150, progress);
                catX = lerp(tree, -65, progress); catLift = 0;
            } else if (t < 24000) {
                phase = DOG_CHASES;
                progress = (t - 16000) / 8000f;
                dogX = lerp(-150, width + 65, progress);
                catX = lerp(-65, width + 150, progress); catLift = 0;
            } else {
                phase = RETURN; dogRight = catRight = false;
                progress = (t - 24000) / 5000f;
                float ease = 1 - (1 - progress) * (1 - progress);
                dogX = lerp(width + 65, approach, ease);
                catX = lerp(width + 150, tree - 15, ease); catLift = 0;
            }
        } else if (t < 31000) {
            phase = TREE_JUMP; dogX = approach;
            progress = (t - 29000) / 2000f;
            catX = lerp(ridingStory ? approach + RIDER_OFFSET : tree - 15, tree, progress);
            catLift = arc(ridingStory ? BACK_HEIGHT : 0, TREE_HEIGHT, 24, progress);
        } else if (t < 33000) {
            phase = SETTLE; dogX = approach;
            progress = (t - 31000) / 2000f;
        } else {
            phase = DOG_HOME; dogRight = false;
            progress = (t - 33000) / 3000f;
            dogX = lerp(approach, bed, progress);
        }
    }

    boolean passenger() {
        return phase == WALK_LEFT || phase == LOOK_LEFT ||
                phase == WALK_RIGHT || phase == LOOK_RIGHT;
    }

    boolean catSleeping() {
        return phase == REST || phase == DOG_HOME || (phase == SETTLE && progress >= 0.6f);
    }

    private static float arc(float from, float to, float height, float t) {
        return lerp(from, to, t) + 4 * height * t * (1 - t);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
