package com.bugsnag.android;

import android.support.annotation.NonNull;
import android.util.JsonReader;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ErrorReader {

    /**
     * Parses an {@link Error} cached as JSON into an Error object.
     *
     * @throws IOException if the file cannot be parsed into a valid JSON object,
     *                     such as if the JSON syntax is invalid or a required
     *                     field is missing.
     */
    static Error readError(@NonNull Configuration config, @NonNull File errorFile)
            throws IOException {
        JsonReader reader = null;

        try {
            User user = null;
            Exceptions exceptions = null;
            Severity severity = Severity.ERROR;
            Session session = null;
            String context = null;
            String groupingHash = null;
            Map<String, Object> appData = null;
            Map<String, Object> deviceData = null;
            MetaData metaData = null;
            ThreadState threadState = null;
            Breadcrumbs crumbs = null;
            ArrayList<String> severityReasonValues = null;
            boolean unhandled = false;

            reader = new JsonReader(new FileReader(errorFile));
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "app":
                        appData = jsonObjectToMap(reader);
                        break;
                    case "breadcrumbs":
                        crumbs = readBreadcrumbs(config, reader);
                        break;
                    case "context":
                        context = reader.nextString();
                        break;
                    case "device":
                        deviceData = jsonObjectToMap(reader);
                        break;
                    case "exceptions":
                        exceptions = readExceptions(config, reader);
                        break;
                    case "groupingHash":
                        groupingHash = reader.nextString();
                        break;
                    case "metaData":
                        metaData = new MetaData(jsonObjectToMap(reader));
                        break;
                    case "session":
                        session = readSession(reader);
                        break;
                    case "severity":
                        severity = Severity.fromString(reader.nextString());
                        break;
                    case "severityReason":
                        severityReasonValues = readSeverityReason(reader);
                        break;
                    case "threads":
                        threadState = readThreadState(config, reader);
                        break;
                    case "unhandled":
                        unhandled = reader.nextBoolean();
                        break;
                    case "user":
                        user = readUser(reader);
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.endObject();
            if (severityReasonValues == null || exceptions == null) {
                throw new IOException("File did not contain a valid error");
            }
            String severityReasonAttribute = severityReasonValues.size() > 1
                ? severityReasonValues.get(1)
                : null;
            HandledState handledState = new HandledState(severityReasonValues.get(0), severity,
                                                         unhandled, severityReasonAttribute);
            Error error = new Error(config, exceptions.getException(), handledState, severity,
                                    session, threadState);
            error.getExceptions().setExceptionType(exceptions.getExceptionType());
            error.setUser(user);
            error.setContext(context);
            error.setGroupingHash(groupingHash);
            error.setAppData(appData);
            error.setMetaData(metaData);
            error.setDeviceData(deviceData);
            error.setBreadcrumbs(crumbs);

            return error;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ex) { /* nothing to do here if this fails */ }
            }
        }
    }

    private static Breadcrumbs readBreadcrumbs(Configuration config, JsonReader reader)
        throws IOException {
        Breadcrumbs crumbs = new Breadcrumbs(config);
        reader.beginArray();
        while (reader.hasNext()) {
            Breadcrumb breadcrumb = readBreadcrumb(reader);

            if (breadcrumb != null) {
                crumbs.add(breadcrumb);
            }
        }
        reader.endArray();
        return crumbs;
    }

    private static Breadcrumb readBreadcrumb(JsonReader reader) throws IOException {
        String name = null;
        String type = null;
        Map<String, String> metadata = new HashMap<>();
        Date captureDate = null;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "name":
                    name = reader.nextString();
                    break;
                case "timestamp":
                    try {
                        captureDate = DateUtils.fromIso8601(reader.nextString());
                    } catch (Exception ex) {
                        throw new IOException("Failed to parse breadcrumb timestamp: ", ex);
                    }
                    break;
                case "type":
                    type = reader.nextString().toUpperCase(Locale.US);
                    break;
                case "metaData":
                    reader.beginObject();
                    while (reader.hasNext()) {
                        metadata.put(reader.nextName(), reader.nextString());
                    }
                    reader.endObject();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }

        reader.endObject();
        if (name != null && captureDate != null && type != null) {
            return new Breadcrumb(name, BreadcrumbType.valueOf(type),
                                      captureDate, metadata);
        } else {
            return null;
        }
    }

    private static Exceptions readExceptions(Configuration config, JsonReader reader)
        throws IOException {
        reader.beginArray();

        BugsnagException root = readException(reader);
        Throwable ref = root; // the latest throwable pulled from the reader

        while (reader.hasNext()) {
            Throwable exc = readException(reader);
            // initialise this throwable as the cause of the previous throwable
            ref.initCause(exc);
            ref = exc;
        }

        reader.endArray();
        Exceptions ex = new Exceptions(config, root);

        if (root != null) {
            ex.setExceptionType(root.getType());
        }
        return ex;
    }

    private static BugsnagException readException(JsonReader reader) throws IOException {
        reader.beginObject();
        String errorClass = null;
        String message = null;
        String type = Configuration.DEFAULT_EXCEPTION_TYPE;
        StackTraceElement[] frames = new StackTraceElement[]{};
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "errorClass":
                    errorClass = reader.nextString();
                    break;
                case "message":
                    message = reader.nextString();
                    break;
                case "stacktrace":
                    frames = readStackFrames(reader);
                    break;
                case "type":
                    type = reader.nextString();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        BugsnagException bugsnagException = new BugsnagException(errorClass, message, frames);
        bugsnagException.setType(type);
        return bugsnagException;
    }


    private static StackTraceElement[] readStackFrames(JsonReader reader) throws IOException {
        ArrayList<StackTraceElement> frames = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            frames.add(readStackFrame(reader));
        }
        reader.endArray();
        return frames.toArray(new StackTraceElement[frames.size()]);
    }

    private static StackTraceElement readStackFrame(JsonReader reader) throws IOException {
        String method = null;
        String file = null;
        int lineNumber = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "method":
                    method = reader.nextString();
                    break;
                case "file":
                    file = reader.nextString();
                    break;
                case "lineNumber":
                    lineNumber = reader.nextInt();
                    break;
                default:
                    reader.skipValue();
                    break;

            }
        }
        reader.endObject();
        return new StackTraceElement("", method, file, lineNumber);
    }

    /**
     * Parses severity reason type and attributes.
     *
     * Returns a list containing the severity reason and attribute value. If the
     * attribute value is null, then the list only contains the severity reason. If
     * severity reason is null then then an {@link IOException} is thrown as the
     * report is invalid.
     *
     * @return the values for severity reason and attribute value if any
     * @throws IOException if the report is missing severity reason
     */
    private static ArrayList<String> readSeverityReason(JsonReader reader) throws IOException {
        reader.beginObject();
        String type = null;
        String attributeValue = null;
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals("type")) {
                type = reader.nextString();
            } else if (name.equals("attributes")) {
                reader.beginObject();
                reader.nextName();
                attributeValue = reader.nextString();
                reader.endObject();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        ArrayList<String> values = new ArrayList<>();
        if (type != null) {
            values.add(type);
        } else {
            throw new IOException("Severity Reason type is required");
        }

        if (attributeValue != null) {
            values.add(attributeValue);
        }
        return values;
    }

    private static Session readSession(JsonReader reader) throws IOException {
        String id = null;
        Date startedAt = null;
        int unhandled = 0;
        int handled = 0;
        User user = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals("id")) {
                id = reader.nextString();
            } else if (name.equals("startedAt")) {
                try {
                    startedAt = DateUtils.fromIso8601(reader.nextString());
                } catch (Exception ex) {
                    throw new IOException("Unable to parse session startedAt: ", ex);
                }
            } else if (name.equals("events")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String eventName = reader.nextName();
                    if (eventName.equals("unhandled")) {
                        unhandled = reader.nextInt();
                    } else if (eventName.equals("handled")) {
                        handled = reader.nextInt();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            } else if (name.equals("user")) {
                user = readUser(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (id != null && startedAt != null) {
            return new Session(id, startedAt, user, unhandled, handled);
        }
        throw new IOException("Session data missing required fields");
    }

    private static User readUser(JsonReader reader) throws IOException {
        User user = new User();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "name":
                    user.setName(reader.nextString());
                    break;
                case "id":
                    user.setId(reader.nextString());
                    break;
                case "email":
                    user.setEmail(reader.nextString());
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();

        return user;
    }

    private static ThreadState readThreadState(Configuration config, JsonReader reader)
        throws IOException {
        List<CachedThread> threads = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            CachedThread cachedThread = readThread(config, reader);

            if (cachedThread != null) {
                threads.add(cachedThread);
            }
        }
        reader.endArray();
        return new ThreadState(threads.toArray(new CachedThread[threads.size()]));
    }

    private static CachedThread readThread(Configuration config,
                                           JsonReader reader) throws IOException {
        long id = 0;
        String name = null;
        String type = null;
        boolean errorReportingThread = false;
        StackTraceElement[] frames = null;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "id":
                    id = reader.nextLong();
                    break;
                case "name":
                    name = reader.nextString();
                    break;
                case "type":
                    type = reader.nextString();
                    break;
                case "stacktrace":
                    frames = readStackFrames(reader);
                    break;
                case "errorReportingThread":
                    errorReportingThread = reader.nextBoolean();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        if (type != null && frames != null) {
            return new CachedThread(config, id, name, type, errorReportingThread, frames);
        } else {
            return null;
        }
    }

    private static Map<String, Object> jsonObjectToMap(JsonReader reader) throws IOException {
        Map<String, Object> data = new HashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            Object value = coerceSerializableFromJSON(reader);
            if (value != null) {
                data.put(key, value);
            }
        }
        reader.endObject();

        return data;
    }

    private static List<Object> jsonArrayToList(JsonReader reader) throws IOException {
        List<Object> objects = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            Object value = coerceSerializableFromJSON(reader);
            if (value != null) {
                objects.add(value);
            }
        }
        reader.endArray();
        return objects;
    }

    private static Object coerceSerializableFromJSON(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                return jsonObjectToMap(reader);
            case STRING:
                return reader.nextString();
            case BOOLEAN:
                return reader.nextBoolean();
            case NUMBER:
                try {
                    return reader.nextInt();
                } catch (NumberFormatException ex) {
                    try {
                        return reader.nextLong();
                    } catch (NumberFormatException ex2) {
                        return reader.nextDouble();
                    }
                }
            case BEGIN_ARRAY:
                return jsonArrayToList(reader);
            default:
                return null;
        }
    }
}
