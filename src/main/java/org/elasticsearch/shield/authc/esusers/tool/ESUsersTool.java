/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers.tool;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.base.Joiner;
import org.elasticsearch.common.cli.CliTool;
import org.elasticsearch.common.cli.CliToolConfig;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.cli.commons.CommandLine;
import org.elasticsearch.common.collect.*;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authc.Realms;
import org.elasticsearch.shield.authc.esusers.ESUsersRealm;
import org.elasticsearch.shield.authc.esusers.FileUserPasswdStore;
import org.elasticsearch.shield.authc.esusers.FileUserRolesStore;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authz.AuthorizationException;
import org.elasticsearch.shield.authz.AuthorizationService;
import org.elasticsearch.shield.authz.Permission;
import org.elasticsearch.shield.authz.store.FileRolesStore;
import org.elasticsearch.transport.TransportRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static org.elasticsearch.common.cli.CliToolConfig.Builder.cmd;
import static org.elasticsearch.common.cli.CliToolConfig.Builder.option;

/**
 *
 */
public class ESUsersTool extends CliTool {

    private static final CliToolConfig CONFIG = CliToolConfig.config("esusers", ESUsersTool.class)
            .cmds(Useradd.CMD, Userdel.CMD, Passwd.CMD, Roles.CMD, ListUsersAndRoles.CMD)
            .build();

    public static void main(String[] args) {
        int status = new ESUsersTool().execute(args);
        System.exit(status);
    }

    public ESUsersTool() {
        super(CONFIG);
    }

    public ESUsersTool(Terminal terminal) {
        super(CONFIG, terminal);
    }

    @Override
    protected Command parse(String cmdName, CommandLine cli) throws Exception {
        switch (cmdName.toLowerCase(Locale.ROOT)) {
            case Useradd.NAME:
                return Useradd.parse(terminal, cli);
            case Userdel.NAME:
                return Userdel.parse(terminal, cli);
            case Passwd.NAME:
                return Passwd.parse(terminal, cli);
            case ListUsersAndRoles.NAME:
                return ListUsersAndRoles.parse(terminal, cli);
            case Roles.NAME:
                return Roles.parse(terminal, cli);
            default:
                assert false : "should never get here, if the user enters an unknown command, an error message should be shown before parse is called";
                return null;
        }
    }

    static class Useradd extends CliTool.Command {

        private static final String NAME = "useradd";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Useradd.class)
                .options(
                        option("p", "password").hasArg(false).required(false),
                        option("r", "roles").hasArg(false).required(false))
                .build();

        public static Command parse(Terminal terminal, CommandLine cli) {
            if (cli.getArgs().length == 0) {
                return exitCmd(ExitStatus.USAGE, terminal, "username is missing");
            }

            String username = cli.getArgs()[0];

            char[] password;
            String passwordStr = cli.getOptionValue("password");
            if (passwordStr != null) {
                password = passwordStr.toCharArray();
            } else {
                password = terminal.readSecret("Enter new password: ");
                char[] retyped = terminal.readSecret("Retype new password: ");
                if (!Arrays.equals(password, retyped)) {
                    return exitCmd(ExitStatus.USAGE, terminal, "Password mismatch");
                }
            }

            String rolesCsv = cli.getOptionValue("roles");
            String[] roles = (rolesCsv != null) ? rolesCsv.split(",") : Strings.EMPTY_ARRAY;
            return new Useradd(terminal, username, new SecuredString(password), roles);
        }

        final String username;
        final SecuredString passwd;
        final String[] roles;

        Useradd(Terminal terminal, String username, SecuredString passwd, String... roles) {
            super(terminal);
            this.username = username;
            this.passwd = passwd;
            this.roles = roles;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            Settings esusersSettings = Realms.internalRealmSettings(settings, ESUsersRealm.TYPE);
            verifyRoles(terminal, settings, env, roles);
            Path file = FileUserPasswdStore.resolveFile(esusersSettings, env);
            Map<String, char[]> users = new HashMap<>(FileUserPasswdStore.parseFile(file, null));
            if (users.containsKey(username)) {
                terminal.println("User [%s] already exists", username);
                return ExitStatus.CODE_ERROR;
            }
            Hasher hasher = Hasher.HTPASSWD;
            users.put(username, hasher.hash(passwd));
            FileUserPasswdStore.writeFile(users, file);

            if (roles != null && roles.length > 0) {
                file = FileUserRolesStore.resolveFile(esusersSettings, env);
                Map<String, String[]> userRoles = new HashMap<>(FileUserRolesStore.parseFile(file, null));
                userRoles.put(username, roles);
                FileUserRolesStore.writeFile(userRoles, file);
            }
            return ExitStatus.OK;
        }
    }

    static class Userdel extends CliTool.Command {

        private static final String NAME = "userdel";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Userdel.class).build();

        public static Command parse(Terminal terminal, CommandLine cli) {
            if (cli.getArgs().length == 0) {
                return exitCmd(ExitStatus.USAGE, terminal, "username is missing");
            }

            String username = cli.getArgs()[0];
            return new Userdel(terminal, username);
        }

        final String username;

        Userdel(Terminal terminal, String username) {
            super(terminal);
            this.username = username;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            Settings esusersSettings = Realms.internalRealmSettings(settings, ESUsersRealm.TYPE);
            Path file = FileUserPasswdStore.resolveFile(esusersSettings, env);
            Map<String, char[]> users = new HashMap<>(FileUserPasswdStore.parseFile(file, null));
            if (!users.containsKey(username)) {
                terminal.println("User [%s] doesn't exist", username);
                return ExitStatus.NO_USER;
            }

            if (Files.exists(file)) {
                char[] passwd = users.remove(username);
                if (passwd != null) {
                    FileUserPasswdStore.writeFile(users, file);
                }
            }

            file = FileUserRolesStore.resolveFile(esusersSettings, env);
            Map<String, String[]> userRoles = new HashMap<>(FileUserRolesStore.parseFile(file, null));
            if (Files.exists(file)) {
                String[] roles = userRoles.remove(username);
                if (roles != null) {
                    FileUserRolesStore.writeFile(userRoles, file);
                }
            }

            return ExitStatus.OK;
        }
    }

    static class Passwd extends CliTool.Command {

        private static final String NAME = "passwd";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Passwd.class)
                .options(option("p", "password").hasArg(false).required(false))
                .build();

        public static Command parse(Terminal terminal, CommandLine cli) {
            if (cli.getArgs().length == 0) {
                return exitCmd(ExitStatus.USAGE, terminal, "username is missing");
            }

            String username = cli.getArgs()[0];

            char[] password;
            String passwordStr = cli.getOptionValue("password");
            if (passwordStr != null) {
                password = passwordStr.toCharArray();
            } else {
                password = terminal.readSecret("Enter new password: ");
                char[] retyped = terminal.readSecret("Retype new password: ");
                if (!Arrays.equals(password, retyped)) {
                    return exitCmd(ExitStatus.USAGE, terminal, "Password mismatch");
                }
            }
            return new Passwd(terminal, username, password);
        }

        final String username;
        final SecuredString passwd;

        Passwd(Terminal terminal, String username, char[] passwd) {
            super(terminal);
            this.username = username;
            this.passwd = new SecuredString(passwd);
            Arrays.fill(passwd, (char) 0);
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            Settings esusersSettings = Realms.internalRealmSettings(settings, ESUsersRealm.TYPE);
            Path file = FileUserPasswdStore.resolveFile(esusersSettings, env);
            Map<String, char[]> users = new HashMap<>(FileUserPasswdStore.parseFile(file, null));
            if (!users.containsKey(username)) {
                terminal.println("User [%s] doesn't exist", username);
                return ExitStatus.NO_USER;
            }
            Hasher hasher = Hasher.HTPASSWD;
            users.put(username, hasher.hash(passwd));
            FileUserPasswdStore.writeFile(users, file);
            return ExitStatus.OK;
        }
    }


    static class Roles extends CliTool.Command {

        private static final String NAME = "roles";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Roles.class)
                .options(
                        option("a", "add").hasArg(true).required(false),
                        option("r", "remove").hasArg(true).required(false))
                .build();

        public static Command parse(Terminal terminal, CommandLine cli) {
            if (cli.getArgs().length == 0) {
                return exitCmd(ExitStatus.USAGE, terminal, "username is missing");
            }

            String username = cli.getArgs()[0];
            String addRolesCsv = cli.getOptionValue("add");
            String[] addRoles = (addRolesCsv != null) ? addRolesCsv.split(",") : Strings.EMPTY_ARRAY;
            String removeRolesCsv = cli.getOptionValue("remove");
            String[] removeRoles = (removeRolesCsv != null) ? removeRolesCsv.split(",") : Strings.EMPTY_ARRAY;

            return new Roles(terminal, username, addRoles, removeRoles);
        }

        public static final Pattern ROLE_PATTERN = Pattern.compile("[\\w@-]+");
        final String username;
        final String[] addRoles;
        final String[] removeRoles;

        public Roles(Terminal terminal, String username, String[] addRoles, String[] removeRoles) {
            super(terminal);
            this.username = username;
            this.addRoles = addRoles;
            this.removeRoles = removeRoles;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            // check if just need to return data as no write operation happens
            // Nothing to add, just list the data for a username
            boolean readOnlyUserListing = removeRoles.length == 0 && addRoles.length == 0;
            if (readOnlyUserListing) {
                return new ListUsersAndRoles(terminal, username).execute(settings, env);
            }

            // check for roles if they match
            String[] allRoles = ObjectArrays.concat(addRoles, removeRoles, String.class);
            for (String role : allRoles) {
                if (!ROLE_PATTERN.matcher(role).matches()) {
                    terminal.println("Role name [%s] is not valid. Please use lowercase and numbers only", role);
                    return ExitStatus.DATA_ERROR;
                }
            }

            Settings esusersSettings = Realms.internalRealmSettings(settings, ESUsersRealm.TYPE);

            Path path = FileUserPasswdStore.resolveFile(esusersSettings, env);
            Map<String, char[]> usersMap = FileUserPasswdStore.parseFile(path, null);
            if (!usersMap.containsKey(username)) {
                terminal.println("User [%s] doesn't exist", username);
                return ExitStatus.NO_USER;
            }

            Path file = FileUserRolesStore.resolveFile(esusersSettings, env);
            Map<String, String[]> userRoles = FileUserRolesStore.parseFile(file, null);

            List<String> roles = Lists.newArrayList();
            if (userRoles.get(username) != null) {
                roles.addAll(Arrays.asList(userRoles.get(username)));
            }
            verifyRoles(terminal, settings, env, addRoles);
            roles.addAll(Arrays.asList(addRoles));
            roles.removeAll(Arrays.asList(removeRoles));

            Map<String, String[]> userRolesToWrite = Maps.newHashMapWithExpectedSize(userRoles.size());
            userRolesToWrite.putAll(userRoles);
            if (roles.size() == 0) {
                userRolesToWrite.remove(username);
            } else {
                userRolesToWrite.put(username, Sets.newLinkedHashSet(roles).toArray(new String[]{}));
            }
            FileUserRolesStore.writeFile(userRolesToWrite, file);

            return ExitStatus.OK;
        }
    }

    static class ListUsersAndRoles extends CliTool.Command {

        private static final String NAME = "list";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Useradd.class).build();

        public static Command parse(Terminal terminal, CommandLine cli) {
            String username = cli.getArgs().length > 0 ? cli.getArgs()[0] : null;
            return new ListUsersAndRoles(terminal, username);
        }

        String username;

        public ListUsersAndRoles(Terminal terminal, String username) {
            super(terminal);
            this.username = username;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            Settings esusersSettings = Realms.internalRealmSettings(settings, ESUsersRealm.TYPE);
            ImmutableMap<String, Permission.Global.Role> knownRoles = loadRoles(terminal, settings, env);
            Path userRolesFilePath = FileUserRolesStore.resolveFile(esusersSettings, env);
            Map<String, String[]> userRoles = FileUserRolesStore.parseFile(userRolesFilePath, null);
            Path userFilePath = FileUserPasswdStore.resolveFile(esusersSettings, env);
            Set<String> users = FileUserPasswdStore.parseFile(userFilePath, null).keySet();

            if (username != null) {
                if (!users.contains(username)) {
                    terminal.println("User [%s] doesn't exist", username);
                    return ExitStatus.NO_USER;
                }

                if (userRoles.containsKey(username)) {
                    String[] roles = userRoles.get(username);
                    Set<String> unknownRoles = Sets.difference(Sets.newHashSet(roles), knownRoles.keySet());
                    String[] markedRoles = markUnknownRoles(roles, unknownRoles);
                    terminal.println("%-15s: %s", username, Joiner.on(",").useForNull("-").join(markedRoles));
                    if (!unknownRoles.isEmpty()) {
                        // at least one role is marked... so printing the legend
                        Path rolesFile = FileRolesStore.resolveFile(esusersSettings, env).toAbsolutePath();
                        terminal.println();
                        terminal.println(" [*]   An unknown role. Please check [%s] to see available roles", rolesFile.toAbsolutePath());
                    }
                } else {
                    terminal.println("%-15s: -", username);
                }
            } else {
                boolean unknownRolesFound = false;
                for (Map.Entry<String, String[]> entry : userRoles.entrySet()) {
                    String[] roles = entry.getValue();
                    Set<String> unknownRoles = Sets.difference(Sets.newHashSet(roles), knownRoles.keySet());
                    String[] markedRoles = markUnknownRoles(roles, unknownRoles);
                    terminal.println("%-15s: %s", entry.getKey(), Joiner.on(",").join(markedRoles));
                    unknownRolesFound = unknownRolesFound || !unknownRoles.isEmpty();
                }
                // list users without roles
                Set<String> usersWithoutRoles = Sets.newHashSet(users);
                usersWithoutRoles.removeAll(userRoles.keySet());
                for (String user : usersWithoutRoles) {
                    terminal.println("%-15s: -", user);
                }

                if (unknownRolesFound) {
                    // at least one role is marked... so printing the legend
                    Path rolesFile = FileRolesStore.resolveFile(esusersSettings, env).toAbsolutePath();
                    terminal.println();
                    terminal.println(" [*]   An unknown role. Please check [%s] to see available roles", rolesFile.toAbsolutePath());
                }
            }

            return ExitStatus.OK;
        }
    }

    private static ImmutableMap<String, Permission.Global.Role> loadRoles(Terminal terminal, Settings settings, Environment env) {
        Path rolesFile = FileRolesStore.resolveFile(settings, env);
        try {
            return FileRolesStore.parseFile(rolesFile, null, new DummyAuthzService());
        } catch (Throwable t) {
            // if for some reason, parsing fails (malformatted perhaps) we just warn
            terminal.println("Warning:  Could not parse [%s] for roles verification. Please revise and fix it. Nonetheless, the user will still be associated with all specified roles", rolesFile.toAbsolutePath());
        }
        return null;
    }

    private static String[] markUnknownRoles(String[] roles, Set<String> unknownRoles) {
        if (unknownRoles.isEmpty()) {
            return roles;
        }
        String[] marked = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            if (unknownRoles.contains(roles[i])) {
                marked[i] = roles[i] + "*";
            } else {
                marked[i] = roles[i];
            }
        }
        return marked;
    }

    private static void verifyRoles(Terminal terminal, Settings settings, Environment env, String[] roles) {
        ImmutableMap<String, Permission.Global.Role> knownRoles = loadRoles(terminal, settings, env);
        if (knownRoles == null) {
            return;
        }
        Set<String> unknownRoles = Sets.difference(Sets.newHashSet(roles), knownRoles.keySet());
        if (!unknownRoles.isEmpty()) {
            Path rolesFile = FileRolesStore.resolveFile(settings, env);
            terminal.println("Warning: The following roles [%s] are unknown. Make sure to add them to the [%s] file. " +
                            "Nonetheless the user will still be associated with all specified roles",
                    Strings.collectionToCommaDelimitedString(unknownRoles), rolesFile.toAbsolutePath());
        }
    }

    private static class DummyAuthzService implements AuthorizationService {
        @Override
        public ImmutableList<String> authorizedIndicesAndAliases(User user, String action) {
            return ImmutableList.of();
        }

        @Override
        public void authorize(User user, String action, TransportRequest request) throws AuthorizationException {

        }
    }
}