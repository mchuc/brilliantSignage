package common;

public class Screen {
    int screenNumber;
    int width;
    int height;

    Screen(int screenNumber, int width, int height) {
        this.screenNumber = screenNumber;
        this.width = width;
        this.height = height;
    }

    public int getScreenNumber() {
        return screenNumber;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
