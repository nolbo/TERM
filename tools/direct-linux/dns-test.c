/* SPDX-License-Identifier: Apache-2.0 */
/* Exercise the real glibc resolver against an unprivileged loopback fixture. */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <arpa/nameser.h>
#include <errno.h>
#include <netdb.h>
#include <resolv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "DNS FAIL %d errno=%d resolver=%d\n", __LINE__, errno, state.res_h_errno); goto failed; } } while (0)

static int answer_is(const unsigned char *answer, int length, ns_type type, const char *name, const char *address)
{
    ns_msg message; ns_rr record;
    if (length < 0 || ns_initparse(answer, length, &message) || ns_msg_count(message, ns_s_an) != 1 ||
            ns_parserr(&message, ns_s_an, 0, &record) || ns_rr_type(record) != type ||
            ns_rr_class(record) != ns_c_in || strcmp(ns_rr_name(record), name)) return 0;
    unsigned char expected[16]; int family = type == ns_t_a ? AF_INET : AF_INET6;
    size_t size = type == ns_t_a ? 4 : 16;
    return inet_pton(family, address, expected) == 1 && ns_rr_rdlen(record) == size &&
        !memcmp(ns_rr_rdata(record), expected, size);
}

int main(int argc, char **argv)
{
    struct __res_state state = {0};
    if (argc != 2) return 2;
    char *end; unsigned long port = strtoul(argv[1], &end, 10);
    if (*end || port <= 1023 || port > 65535) return 2;
    if (res_ninit(&state)) return 3;
    // The fixture overrides only its own resolver state, never host DNS or a
    // user's distribution. No privileged port or public resolver is required.
    state.nscount = 1;
    state.nsaddr_list[0] = (struct sockaddr_in){.sin_family = AF_INET, .sin_port = htons(port),
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    state.retrans = 1; state.retry = 1;
    state.options |= RES_RECURSE | RES_DEFNAMES | RES_DNSRCH | RES_USE_EDNS0;
    state.options &= ~(RES_USEVC | RES_IGNTC);
    char search[] = "fixture.test";
    state.dnsrch[0] = search; state.dnsrch[1] = NULL;
    unsigned char answer[1024];
    int length = res_nquery(&state, "address.fixture.test", ns_c_in, ns_t_a, answer, sizeof(answer));
    CHECK(answer_is(answer, length, ns_t_a, "address.fixture.test", "192.0.2.123"));
    length = res_nquery(&state, "address.fixture.test", ns_c_in, ns_t_aaaa, answer, sizeof(answer));
    CHECK(answer_is(answer, length, ns_t_aaaa, "address.fixture.test", "2001:db8::123"));
    CHECK(res_nquery(&state, "missing.fixture.test", ns_c_in, ns_t_a, answer, sizeof(answer)) == -1 &&
        state.res_h_errno == HOST_NOT_FOUND);
    length = res_nquery(&state, "truncated.fixture.test", ns_c_in, ns_t_a, answer, sizeof(answer));
    CHECK(answer_is(answer, length, ns_t_a, "truncated.fixture.test", "192.0.2.123"));
    length = res_nsearch(&state, "search", ns_c_in, ns_t_a, answer, sizeof(answer));
    CHECK(answer_is(answer, length, ns_t_a, "search.fixture.test", "192.0.2.123"));
    res_nclose(&state);
    puts("PASS glibc DNS A/AAAA/NXDOMAIN/search/TCP retry");
    return 0;
failed:
    res_nclose(&state);
    return 1;
}
