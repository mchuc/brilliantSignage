package interfaces;

import common.Screen;

public interface ISettings {


    int getScreenWidth();
    int getScreenHeight();
    int getScreenCurrent();
    void  setScreenCurrent(int screenNumber);
    Screen[] getScreensAvailable();
    void setDirectory(String directory);
    String getDirectory();
    void setSMILDebug(boolean enabled);
    void setSMILPlayerName(String playerName);
    int getSMILRecommendedRefreshSeconds();
    String setSMILHost(String host);

}
