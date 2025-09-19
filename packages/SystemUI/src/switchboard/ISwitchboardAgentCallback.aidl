package switchboard;

interface ISwitchboardAgentCallback {
    void onResponse(String context, int conversationId);
    void onError(int code, String error);
    void onProgressUpdate(String update);
}
