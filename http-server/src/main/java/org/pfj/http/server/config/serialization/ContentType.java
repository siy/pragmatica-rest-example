package org.pfj.http.server.config.serialization;

public enum ContentType {
    TEXT_PLAIN("text/plain; charset=UTF-8"),
    APPLICATION_JSON("application/json; charset=UTF-8");

    private String text;

    ContentType(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
