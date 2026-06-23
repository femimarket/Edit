//
//  DateBucketTests.swift
//  ImageEditTests
//

import Testing
import Foundation
@testable import ImageEdit

@Suite("DateBucket")
struct DateBucketTests {

    /// Fixed reference "now" so the tests are deterministic regardless
    /// of the wall clock. 2026-06-22 12:00:00 UTC.
    private let now: Date = {
        var c = DateComponents()
        c.year = 2026; c.month = 6; c.day = 22
        c.hour = 12; c.minute = 0; c.second = 0
        c.timeZone = TimeZone(identifier: "UTC")
        return Calendar(identifier: .gregorian).date(from: c)!
    }()

    private func daysAgo(_ days: Int, hours: Int = 0) -> Date {
        var comps = DateComponents()
        comps.day = -days
        comps.hour = -hours
        return Calendar.current.date(byAdding: comps, to: now)!
    }

    @Test func today() {
        #expect(DateBucket.from(now, now: now) == .today)
        #expect(DateBucket.from(daysAgo(0, hours: 3), now: now) == .today)
    }

    @Test func yesterday() {
        #expect(DateBucket.from(daysAgo(1), now: now) == .yesterday)
    }

    @Test func lastWeekBoundary() {
        // 2 through 7 days ago bucket into lastWeek.
        #expect(DateBucket.from(daysAgo(2), now: now) == .lastWeek)
        #expect(DateBucket.from(daysAgo(7), now: now) == .lastWeek)
    }

    @Test func lastMonthBoundary() {
        // 8 through 30 days ago bucket into lastMonth.
        #expect(DateBucket.from(daysAgo(8), now: now) == .lastMonth)
        #expect(DateBucket.from(daysAgo(30), now: now) == .lastMonth)
    }

    @Test func older() {
        #expect(DateBucket.from(daysAgo(31), now: now) == .older)
        #expect(DateBucket.from(daysAgo(365), now: now) == .older)
        #expect(DateBucket.from(.distantPast, now: now) == .older)
    }

    @Test func allCasesOrderedNewestFirst() {
        // The enum's declaration order is the rendering order. Anyone
        // reordering this enum needs to look at the picker's group
        // rendering too.
        #expect(DateBucket.allCases == [.today, .yesterday, .lastWeek, .lastMonth, .older])
    }
}
