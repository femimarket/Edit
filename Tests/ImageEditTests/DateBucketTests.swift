//
//  DateBucketTests.swift
//  ImageEditTests
//

import Testing
import Foundation
@testable import ImageEdit

@Suite("DateBucket")
struct DateBucketTests {

    private func date(daysAgo days: Int) -> Date {
        Calendar.current.date(byAdding: .day, value: -days, to: Date())!
    }

    @Test func today() {
        #expect(DateBucket.from(Date()) == .today)
    }

    @Test func yesterday() {
        #expect(DateBucket.from(date(daysAgo: 1)) == .yesterday)
    }

    @Test func lastWeekBoundary() {
        #expect(DateBucket.from(date(daysAgo: 2)) == .lastWeek)
        #expect(DateBucket.from(date(daysAgo: 7)) == .lastWeek)
    }

    @Test func lastMonthBoundary() {
        #expect(DateBucket.from(date(daysAgo: 8)) == .lastMonth)
        #expect(DateBucket.from(date(daysAgo: 30)) == .lastMonth)
    }

    @Test func older() {
        #expect(DateBucket.from(date(daysAgo: 31)) == .older)
        #expect(DateBucket.from(date(daysAgo: 365)) == .older)
        #expect(DateBucket.from(.distantPast) == .older)
    }

    @Test func allCasesOrderedNewestFirst() {
        #expect(DateBucket.allCases == [.today, .yesterday, .lastWeek, .lastMonth, .older])
    }
}
