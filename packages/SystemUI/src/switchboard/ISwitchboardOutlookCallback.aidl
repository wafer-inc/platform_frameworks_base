package switchboard;

interface ISwitchboardOutlookCallback {
    void onResponse(String context);
    void onError(int code, String error);
}
