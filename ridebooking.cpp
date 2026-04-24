#include <algorithm>
#include <iostream>
#include <limits>
#include <vector>

using namespace std;

struct Edge
{
    int u, v;
    double w;
};

struct Driver
{
    int node;
    double rating, pricePerKm;
    int type;
    bool available = true;
};

struct Rider
{
    int node;
};

vector<double> bellmanFord(int V, const vector<Edge> &edges, int src)

{
    const double INF = numeric_limits<double>::max() / 4.0; 

    vector<double> dist(V, INF);

    if (src < 0 || src >= V)
    {

        return dist;
    }

    dist[src] = 0;

    for (int i = 0; i < V - 1; i++)
    {

        bool updated = false;

        for (const auto &e : edges)
        {

            if (e.u < 0 || e.u >= V || e.v < 0 || e.v >= V)
            {
                continue;
            }

            if (dist[e.u] < INF && dist[e.u] + e.w < dist[e.v])
            {

                dist[e.v] = dist[e.u] + e.w;

                updated = true;
            }
        }

        if (!updated)
        {
            break;
        }
    }

    return dist;
}

string vehicleName(int t)

{
    static const string vehicles[] = {"Bike", "Mini", "Sedan", "SUV"};

    if (t < 0 || t >= 4)
    {
        return "Unknown";
    }

    return vehicles[t];
}

int main()
{
    int V, E;
    cout << "Enter nodes and edges: ";
    cin >> V >> E;

    if (V <= 0 || E < 0)
    {
        cout << "Invalid graph size.\n";
        return 1;
    }

    vector<Edge> edges(E);

    cout << "Enter edges (u v weight):\n";

    for (int i = 0; i < E; i++)
    {

        cin >> edges[i].u >> edges[i].v >> edges[i].w;

    }

    int d, r;
    cout << "Enter drivers and riders: ";
    cin >> d >> r;

    if (d < 0 || r < 0)
    {
        cout << "Invalid driver/rider counts.\n";
        return 1;
    }

    vector<Driver> drivers(d);

    vector<Rider> riders(r);

    cout << "\nEnter Driver (node rating price type):\n";

    for (int i = 0; i < d; i++)
    {
        cin >> drivers[i].node >> drivers[i].rating >> drivers[i].pricePerKm >>
            drivers[i].type;
    }

    cout << "\nEnter Rider nodes:\n";

    for (int i = 0; i < r; i++)
    {
        cin >> riders[i].node;
    }

    for (int i = 0; i < r; i++)
    {
        cout << "\n--- Rider " << i << " ---\n";

        if (riders[i].node < 0 || riders[i].node >= V)
        {
            cout << "Rider node is out of bounds.\n";

            continue;
        }

        int pref;
        cout << "Select Type (0 Bike,1 Mini,2 Sedan,3 SUV): ";
        cin >> pref;

        if (pref < 0 || pref > 3)
        {
            cout << "Invalid vehicle type!\n";

            continue;
        }

        vector<double> dist = bellmanFord(V, edges, riders[i].node);

        struct Option

        {
            int id;
            double dist;
            double fare;
            double rating;

        };

        vector<Option> opt;

        const double INF = numeric_limits<double>::max() / 4.0;

        for (int j = 0; j < d; j++)
        {
            if (!drivers[j].available || drivers[j].type != pref)

            {
                continue;
            }

            if (drivers[j].node < 0 || drivers[j].node >= V)
            {
                continue;
            }

            if (dist[drivers[j].node] < INF)
            {
                opt.push_back(

                    {j,
                     dist[drivers[j].node],
                     dist[drivers[j].node] * drivers[j].pricePerKm,
                     drivers[j].rating});

            }

        }

        if (opt.empty())
        {
            cout << "No drivers available!\n";
            continue;
        }

        sort(opt.begin(), opt.end(),
             [](const Option &a, const Option &b)
             { return a.fare < b.fare; });

        int limit = min(3, static_cast<int>(opt.size()));

        cout << "\nTop Drivers:\n";

        for (int k = 0; k < limit; k++)
        {
            int id = opt[k].id;

            cout << k << ". Driver " << id << " (" << vehicleName(drivers[id].type)
                 << ")"
                 << " | Dist: " << opt[k].dist << " | Fare: Rs " << opt[k].fare
                 << " | Rating: " << opt[k].rating << '\n';

        }

        int choice;
        cout << "Choose (0-" << limit - 1 << "): ";
        cin >> choice;

        if (choice >= 0 && choice < limit)
        {
            int sel = opt[choice].id;

            drivers[sel].available = false;

            drivers[sel].node = riders[i].node;

            cout << "Driver " << sel << " assigned!\n";
        }

        else
        {
            cout << "Invalid choice!\n";
        }

    }

    cout << "\nAll rides completed.\n";

    return 0;
}
