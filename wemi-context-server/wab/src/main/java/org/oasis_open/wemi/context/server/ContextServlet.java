package org.oasis_open.wemi.context.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.io.IOUtils;
import org.oasis_open.wemi.context.server.api.Event;
import org.oasis_open.wemi.context.server.api.Persona;
import org.oasis_open.wemi.context.server.api.Session;
import org.oasis_open.wemi.context.server.api.User;
import org.oasis_open.wemi.context.server.api.services.EventService;
import org.oasis_open.wemi.context.server.api.services.SegmentService;
import org.oasis_open.wemi.context.server.api.services.UserService;
import org.ops4j.pax.cdi.api.OsgiService;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Date;
import java.util.UUID;

/**
 * A servlet filter to serve a context-specific Javascript containing the current request context object.
 */
@WebServlet(urlPatterns = {"/context.js"})
public class ContextServlet extends HttpServlet {

    public static final String BASE_SCRIPT_LOCATION = "/WEB-INF/javascript/base.js";
    public static final String SESSIONID_PERSONA_PREFIX = "persona-";
    public static final String SESSIONID_PERSONA_SEPARATOR = "___";

    @Inject
    @OsgiService
    UserService userService;

    @Inject
    @OsgiService
    SegmentService segmentService;

    @Inject
    @OsgiService
    private EventService eventService;

    @Override
    public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        final Date timestamp = new Date();
        if (request.getParameter("timestamp") != null) {
            timestamp.setTime(Long.parseLong(request.getParameter("timestamp")));
        }
        // first we must retrieve the context for the current visitor, and build a Javascript object to attach to the
        // script output.
        String visitorId = null;

        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        String httpMethod = httpServletRequest.getMethod();
//        HttpUtils.dumpBasicRequestInfo(httpServletRequest);
//        HttpUtils.dumpRequestHeaders(httpServletRequest);

        if ("options".equals(httpMethod.toLowerCase())) {
            HttpUtils.setupCORSHeaders(httpServletRequest, response);
            return;
        }

        User user = null;

        String cookieProfileId = null;
        String cookiePersonaId = null;
        Cookie[] cookies = httpServletRequest.getCookies();
        // HttpUtils.dumpRequestCookies(cookies);
        for (Cookie cookie : cookies) {
            if ("wemi-profile-id".equals(cookie.getName())) {
                cookieProfileId = cookie.getValue();
            } else if ("wemi-persona-id".equals(cookie.getName())) {
                cookiePersonaId = cookie.getValue();
            }
        }

        String personaId = request.getParameter("personaId");
        if (personaId != null) {
            if ("currentUser".equals(personaId) || personaId.equals(cookieProfileId)) {
                user = null;
                HttpUtils.clearCookie(response, "wemi-persona-id");
                personaId = null;
            } else {
                user = userService.loadPersona(personaId);
                if (user != null) {
                    HttpUtils.sendCookie(user, response);
                }
            }
        } else if (cookiePersonaId != null) {
            user = userService.loadPersona(cookiePersonaId);
            personaId = cookiePersonaId;
        }

        String sessionId = request.getParameter("sessionId");
        if (personaId != null) {
            sessionId = SESSIONID_PERSONA_PREFIX + personaId + SESSIONID_PERSONA_SEPARATOR + sessionId;
        }

        Session session = null;

        if (sessionId != null) {
            session = userService.loadSession(sessionId);
            if (session != null) {
                visitorId = session.getUserId();
                if (user == null) { // could be non null in case of persona
                    user = userService.load(visitorId);
                }
            }
        }
        if (user == null) {
            boolean userCreated = false;
            // user not stored in session
            if (cookieProfileId == null) {
                // no visitorId cookie was found, we generate a new one and create the user in the user service
                user = createNewUser(null, response);
                userCreated = true;
            } else {
                user = userService.load(cookieProfileId);
                if (user == null) {
                    // this can happen if we have an old cookie but have reset the server.
                    user = createNewUser(cookieProfileId, response);
                    userCreated = true;
                }
            }

            if (userCreated) {
                Event userUpdated = new Event("userUpdated", session, user, timestamp);
                if (user instanceof Persona) {
                    request = new PersonaRequestWrapper(httpServletRequest, (Persona) user);
                }
                userUpdated.getAttributes().put("http_request", request);
                userUpdated.getAttributes().put("http_response", response);
                eventService.save(userUpdated);
            }
        } else if (cookieProfileId == null || !cookieProfileId.equals(user.getItemId())) {
            // user if stored in session but not in cookie
            HttpUtils.sendCookie(user, response);
        }

        // associate user with session
        if (sessionId != null && session == null) {
            session = new Session(sessionId, user, timestamp);
            userService.saveSession(session);
            Event event = new Event("sessionCreated", session, user, timestamp);
            if (user instanceof Persona) {
                request = new PersonaRequestWrapper(httpServletRequest, (Persona) user);
            }
            event.getAttributes().put("http_request", request);
            event.getAttributes().put("http_response", response);
            eventService.save(event);

            if (user instanceof Persona) {
                Event impersonateEvent = new Event("impersonate", session, user, timestamp);
                request = new PersonaRequestWrapper(httpServletRequest, (Persona) user);
                impersonateEvent.getAttributes().put("http_request", request);
                impersonateEvent.getAttributes().put("http_response", response);
                eventService.save(impersonateEvent);
            }
        }

        HttpUtils.setupCORSHeaders(httpServletRequest, response);

        Writer responseWriter = response.getWriter();

        String baseRequestURL = HttpUtils.getBaseRequestURL(httpServletRequest);

        // we re-use the object naming convention from http://www.w3.org/community/custexpdata/, specifically in
        // http://www.w3.org/2013/12/ceddl-201312.pdf
        responseWriter.append("window.digitalData = window.digitalData || {};\n");
        responseWriter.append("var wemi = {\n");
        responseWriter.append("    wemiDigitalData : \n");
        final String jsonDigitalData = HttpUtils.getJSONDigitalData(user, session, baseRequestURL);
        responseWriter.append(jsonDigitalData);
        responseWriter.append(", \n");

        if (sessionId != null) {
            responseWriter.append("    sessionId : '" + sessionId + "'\n");
        }

        if ("post".equals(httpMethod.toLowerCase())) {
            StringBuilder buffer = new StringBuilder();
            String line;
            BufferedReader reader = request.getReader();
            while ((line = reader.readLine()) != null) {
                buffer.append(line);
            }
            if (buffer.length() > 0) {
                ObjectMapper mapper = new ObjectMapper();
                JsonFactory factory = mapper.getFactory();
                ArrayNode filterNodes = mapper.readTree(factory.createParser(buffer.toString()));
                responseWriter.append("    , filteringResults : {");
                boolean first = true;
                for (JsonNode jsonNode : filterNodes) {
                    String id = jsonNode.get("filterid").asText();
                    ArrayNode filters = (ArrayNode) jsonNode.get("filters");
                    boolean result = true;
                    for (JsonNode filter : filters) {
                        JsonNode condition = filter.get("condition");
                        result &= userService.matchCondition(mapper.writeValueAsString(condition), user, session);
                    }
                    responseWriter.append((first ? "" : ",") + "'" + id + "':" + result);
                    first = false;
                }
                responseWriter.append("}\n");
            }
        }
        responseWriter.append("};\n");

        // now we copy the base script source code
        InputStream baseScriptStream = getServletContext().getResourceAsStream(BASE_SCRIPT_LOCATION);
        IOUtils.copy(baseScriptStream, responseWriter);

        responseWriter.flush();

    }

    private User createNewUser(String existingVisitorId, ServletResponse response) {
        User user;
        String visitorId = existingVisitorId;
        if (visitorId == null) {
            visitorId = UUID.randomUUID().toString();
        }
        user = new User(visitorId);
        userService.save(user);
        HttpUtils.sendCookie(user, response);
        return user;
    }


    public void destroy() {
    }
}
