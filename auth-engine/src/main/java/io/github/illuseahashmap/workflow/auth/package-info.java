/**
 * User-facing authentication and authorization boundary.
 *
 * <p>This module owns browser/user login, registration, roles, menus, and permissions. The
 * workflow-engine module depends on it, while auth-engine depends only on shared-kernel and
 * infrastructure libraries. Service-to-service request signing remains in workflow-engine's
 * security boundary until a dedicated gateway or service-auth module is introduced.</p>
 */
package io.github.illuseahashmap.workflow.auth;
