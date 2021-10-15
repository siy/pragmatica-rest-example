package org.pfj.http.server;

import io.netty.handler.codec.http.HttpMethod;
import org.pfj.lang.Option;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.pfj.lang.Option.option;

//WARNING: dynamic route adding is not supported
public final class EndpointTable {
    private final Map<HttpMethod, TreeMap<String, Route<?>>> routes = new HashMap<>();

    public static EndpointTable create() {
        return new EndpointTable();
    }

    public static EndpointTable with(Route<?>... routes) {
        var table = new EndpointTable();

        for (var route : routes) {
            table.add(route);
        }

        return table;
    }

    public EndpointTable add(Route<?> route) {
        routes.compute(route.method(), (method, pathMap) -> {
            var map = pathMap == null ? new TreeMap<String, Route<?>>() : pathMap;
            map.put(route.path(), route);
            return map;
        });

        return this;
    }

    public Option<Route<?>> findRoute(HttpMethod method, String path) {
        return option(routes.get(method))
            .flatMap(map -> option(map.ceilingEntry(path)))
            .filter(routeEntry -> isSameOrStartOfPath(path, routeEntry.getKey()))
            .map(Map.Entry::getValue);
    }

    private boolean isSameOrStartOfPath(String inputPath, String routePath) {
        return (inputPath.length() == routePath.length() && inputPath.equals(routePath))
            || (inputPath.length() > routePath.length() && inputPath.charAt(routePath.length()) == '/');
    }
}
