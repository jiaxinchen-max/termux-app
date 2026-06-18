package com.termux.app.activities.termuxbox;

public interface TermuxBoxNavigator {
    void openSection(TermuxBoxSection section);
    TermuxBoxRepository getRepository();
}
